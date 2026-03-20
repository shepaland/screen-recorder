using System.Runtime.InteropServices;
using Microsoft.Extensions.Logging;

namespace KaderoAgent.Audit;

/// <summary>
/// Receives WTS session notifications (lock/unlock/logon/logoff) via a message-only window.
/// Works in Windows Service (Session 0) without UI — uses HWND_MESSAGE parent and a dedicated
/// STA thread with a message pump. This replaces SystemEvents.SessionSwitch which requires
/// a UI message loop that doesn't exist in a headless Windows Service.
/// </summary>
public sealed class WtsSessionNotifier : IDisposable
{
    // ── P/Invoke: WTS ───────────────────────────────────────────────────────
    [DllImport("wtsapi32.dll", SetLastError = true)]
    private static extern bool WTSRegisterSessionNotification(IntPtr hWnd, int dwFlags);

    [DllImport("wtsapi32.dll", SetLastError = true)]
    private static extern bool WTSUnRegisterSessionNotification(IntPtr hWnd);

    // ── P/Invoke: Window ────────────────────────────────────────────────────
    private delegate IntPtr WndProcDelegate(IntPtr hWnd, uint msg, IntPtr wParam, IntPtr lParam);

    [DllImport("user32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern ushort RegisterClassW(ref WNDCLASS lpWndClass);

    [DllImport("user32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern IntPtr CreateWindowExW(
        uint dwExStyle, string lpClassName, string lpWindowName, uint dwStyle,
        int x, int y, int nWidth, int nHeight,
        IntPtr hWndParent, IntPtr hMenu, IntPtr hInstance, IntPtr lpParam);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool DestroyWindow(IntPtr hWnd);

    [DllImport("user32.dll")]
    private static extern IntPtr DefWindowProcW(IntPtr hWnd, uint msg, IntPtr wParam, IntPtr lParam);

    [DllImport("user32.dll")]
    private static extern bool GetMessageW(out MSG lpMsg, IntPtr hWnd, uint wMsgFilterMin, uint wMsgFilterMax);

    [DllImport("user32.dll")]
    private static extern bool TranslateMessage(ref MSG lpMsg);

    [DllImport("user32.dll")]
    private static extern IntPtr DispatchMessageW(ref MSG lpMsg);

    [DllImport("user32.dll")]
    private static extern bool PostMessageW(IntPtr hWnd, uint msg, IntPtr wParam, IntPtr lParam);

    [DllImport("kernel32.dll")]
    private static extern IntPtr GetModuleHandle(string? lpModuleName);

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct WNDCLASS
    {
        public uint style;
        public WndProcDelegate lpfnWndProc;
        public int cbClsExtra;
        public int cbWndExtra;
        public IntPtr hInstance;
        public IntPtr hIcon;
        public IntPtr hCursor;
        public IntPtr hbrBackground;
        public string? lpszMenuName;
        public string lpszClassName;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct MSG
    {
        public IntPtr hwnd;
        public uint message;
        public IntPtr wParam;
        public IntPtr lParam;
        public uint time;
        public int pt_x;
        public int pt_y;
    }

    // ── Constants ────────────────────────────────────────────────────────────
    private static readonly IntPtr HWND_MESSAGE = new(-3);
    private const int NOTIFY_FOR_ALL_SESSIONS = 1;
    private const uint WM_WTSSESSION_CHANGE = 0x02B1;
    private const uint WM_QUIT = 0x0012;

    // WParam values for WM_WTSSESSION_CHANGE
    private const int WTS_CONSOLE_CONNECT = 0x1;
    private const int WTS_CONSOLE_DISCONNECT = 0x2;
    private const int WTS_REMOTE_CONNECT = 0x3;
    private const int WTS_REMOTE_DISCONNECT = 0x4;
    private const int WTS_SESSION_LOGON = 0x5;
    private const int WTS_SESSION_LOGOFF = 0x6;
    private const int WTS_SESSION_LOCK = 0x7;
    private const int WTS_SESSION_UNLOCK = 0x8;

    // ── Event args ──────────────────────────────────────────────────────────
    public enum SessionEvent
    {
        Lock,
        Unlock,
        Logon,
        Logoff,
        ConsoleConnect,
        ConsoleDisconnect,
        RemoteConnect,
        RemoteDisconnect
    }

    public class SessionChangeEventArgs : EventArgs
    {
        public SessionEvent Event { get; }
        public int SessionId { get; }
        public SessionChangeEventArgs(SessionEvent evt, int sessionId) { Event = evt; SessionId = sessionId; }
    }

    public event EventHandler<SessionChangeEventArgs>? SessionChanged;

    // ── Fields ──────────────────────────────────────────────────────────────
    private readonly ILogger _logger;
    private Thread? _thread;
    private IntPtr _hwnd;
    private volatile bool _disposed;
    // Must prevent GC of delegate while native code holds a pointer to it
    private WndProcDelegate? _wndProcDelegate;

    public bool IsRunning => _thread?.IsAlive == true && _hwnd != IntPtr.Zero;

    public WtsSessionNotifier(ILogger<WtsSessionNotifier> logger)
    {
        _logger = logger;
    }

    /// <summary>Start the message-only window thread. Safe to call multiple times.</summary>
    public void Start()
    {
        if (_thread?.IsAlive == true) return;

        var ready = new ManualResetEventSlim(false);
        Exception? startError = null;

        _thread = new Thread(() =>
        {
            try
            {
                CreateMessageWindow();
                ready.Set();
                RunMessageLoop();
            }
            catch (Exception ex)
            {
                startError = ex;
                ready.Set();
            }
        })
        {
            IsBackground = true,
            Name = "WtsSessionNotifier"
        };
        _thread.SetApartmentState(ApartmentState.STA);
        _thread.Start();

        // Wait for window creation (max 10s)
        ready.Wait(TimeSpan.FromSeconds(10));
        if (startError != null)
            _logger.LogError(startError, "WtsSessionNotifier failed to start");
        else
            _logger.LogInformation("WtsSessionNotifier started, hwnd=0x{Hwnd:X}", _hwnd);
    }

    private void CreateMessageWindow()
    {
        var hInstance = GetModuleHandle(null);
        var className = $"KaderoWtsNotifier_{Environment.ProcessId}";

        _wndProcDelegate = WndProc;

        var wc = new WNDCLASS
        {
            lpfnWndProc = _wndProcDelegate,
            hInstance = hInstance,
            lpszClassName = className
        };

        var atom = RegisterClassW(ref wc);
        if (atom == 0)
        {
            var err = Marshal.GetLastWin32Error();
            throw new InvalidOperationException($"RegisterClass failed: Win32 error {err}");
        }

        _hwnd = CreateWindowExW(
            0, className, "KaderoWtsNotifier", 0,
            0, 0, 0, 0,
            HWND_MESSAGE, IntPtr.Zero, hInstance, IntPtr.Zero);

        if (_hwnd == IntPtr.Zero)
        {
            var err = Marshal.GetLastWin32Error();
            throw new InvalidOperationException($"CreateWindowEx failed: Win32 error {err}");
        }

        if (!WTSRegisterSessionNotification(_hwnd, NOTIFY_FOR_ALL_SESSIONS))
        {
            var err = Marshal.GetLastWin32Error();
            _logger.LogWarning("WTSRegisterSessionNotification failed: Win32 error {Error}. " +
                "Session events may not be received.", err);
        }
    }

    private void RunMessageLoop()
    {
        while (!_disposed && GetMessageW(out var msg, IntPtr.Zero, 0, 0))
        {
            TranslateMessage(ref msg);
            DispatchMessageW(ref msg);
        }

        // Cleanup
        if (_hwnd != IntPtr.Zero)
        {
            WTSUnRegisterSessionNotification(_hwnd);
            DestroyWindow(_hwnd);
            _hwnd = IntPtr.Zero;
        }
    }

    private IntPtr WndProc(IntPtr hWnd, uint msg, IntPtr wParam, IntPtr lParam)
    {
        if (msg == WM_WTSSESSION_CHANGE)
        {
            var sessionId = (int)lParam;
            var evt = (int)wParam switch
            {
                WTS_SESSION_LOCK => SessionEvent.Lock,
                WTS_SESSION_UNLOCK => SessionEvent.Unlock,
                WTS_SESSION_LOGON => SessionEvent.Logon,
                WTS_SESSION_LOGOFF => SessionEvent.Logoff,
                WTS_CONSOLE_CONNECT => SessionEvent.ConsoleConnect,
                WTS_CONSOLE_DISCONNECT => SessionEvent.ConsoleDisconnect,
                WTS_REMOTE_CONNECT => SessionEvent.RemoteConnect,
                WTS_REMOTE_DISCONNECT => SessionEvent.RemoteDisconnect,
                _ => (SessionEvent?)null
            };

            if (evt.HasValue)
            {
                _logger.LogDebug("WTS event: {Event}, sessionId={SessionId}", evt.Value, sessionId);
                try
                {
                    SessionChanged?.Invoke(this, new SessionChangeEventArgs(evt.Value, sessionId));
                }
                catch (Exception ex)
                {
                    _logger.LogError(ex, "Error in SessionChanged handler for {Event}", evt.Value);
                }
            }
            return IntPtr.Zero;
        }

        return DefWindowProcW(hWnd, msg, wParam, lParam);
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;

        // Post WM_QUIT to break the message loop
        if (_hwnd != IntPtr.Zero)
            PostMessageW(_hwnd, WM_QUIT, IntPtr.Zero, IntPtr.Zero);

        _thread?.Join(TimeSpan.FromSeconds(5));
        _thread = null;
    }
}
