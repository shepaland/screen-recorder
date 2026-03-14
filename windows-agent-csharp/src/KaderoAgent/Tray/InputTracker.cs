using System.Collections.Concurrent;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;
using KaderoAgent.Ipc;

namespace KaderoAgent.Tray;

/// <summary>
/// Tracks mouse clicks in the interactive user session (tray process).
/// Uses low-level mouse hook (WH_MOUSE_LL) to capture click events.
/// Sends batched events to service via Named Pipe every 30 seconds.
/// </summary>
public class InputTracker : IDisposable
{
    private const int WH_MOUSE_LL = 14;
    private const int WM_LBUTTONDOWN = 0x0201;
    private const int WM_RBUTTONDOWN = 0x0204;
    private const int WM_MBUTTONDOWN = 0x0207;
    private const int WM_LBUTTONDBLCLK = 0x0203;

    private const int SendIntervalMs = 30_000;
    private const int MaxClicksPerSecond = 100;
    private const int DoubleClickThresholdMs = 500;
    private const int DoubleClickDistancePx = 5;

    private static readonly log4net.ILog _log = log4net.LogManager.GetLogger(typeof(InputTracker));

    private delegate IntPtr LowLevelMouseProc(int nCode, IntPtr wParam, IntPtr lParam);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern IntPtr SetWindowsHookEx(int idHook, LowLevelMouseProc lpfn, IntPtr hMod, uint dwThreadId);

    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool UnhookWindowsHookEx(IntPtr hhk);

    [DllImport("user32.dll")]
    private static extern IntPtr CallNextHookEx(IntPtr hhk, int nCode, IntPtr wParam, IntPtr lParam);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)]
    private static extern IntPtr GetModuleHandle(string lpModuleName);

    [DllImport("user32.dll")]
    private static extern IntPtr GetForegroundWindow();

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern int GetWindowText(IntPtr hWnd, StringBuilder sb, int maxCount);

    [DllImport("user32.dll")]
    private static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint lpdwProcessId);

    [StructLayout(LayoutKind.Sequential)]
    private struct MSLLHOOKSTRUCT
    {
        public int X;
        public int Y;
        public uint mouseData;
        public uint flags;
        public uint time;
        public IntPtr dwExtraInfo;
    }

    private IntPtr _hookHandle = IntPtr.Zero;
    private LowLevelMouseProc? _hookProc; // prevent GC of delegate
    private readonly PipeClient _pipe = new();
    private System.Threading.Timer? _sendTimer;

    private readonly ConcurrentQueue<InputEventData> _pending = new();
    private int _clicksThisSecond;
    private long _lastClickSecond;

    // Double-click detection
    private DateTime _lastClickTime;
    private int _lastClickX, _lastClickY;
    private string _lastClickButton = "";

    public void Start()
    {
        _hookProc = HookCallback;

        using var curProcess = Process.GetCurrentProcess();
        using var curModule = curProcess.MainModule!;
        _hookHandle = SetWindowsHookEx(WH_MOUSE_LL, _hookProc, GetModuleHandle(curModule.ModuleName!), 0);

        if (_hookHandle == IntPtr.Zero)
        {
            _log.Error($"SetWindowsHookEx failed: {Marshal.GetLastWin32Error()}");
            return;
        }

        _log.Info("Mouse hook installed");

        _sendTimer = new System.Threading.Timer(_ => SendPendingAsync().Wait(), null, SendIntervalMs, SendIntervalMs);
    }

    private IntPtr HookCallback(int nCode, IntPtr wParam, IntPtr lParam)
    {
        if (nCode >= 0)
        {
            int msg = wParam.ToInt32();
            if (msg == WM_LBUTTONDOWN || msg == WM_RBUTTONDOWN || msg == WM_MBUTTONDOWN || msg == WM_LBUTTONDBLCLK)
            {
                // Rate limit
                long nowSecond = Environment.TickCount64 / 1000;
                if (nowSecond != _lastClickSecond)
                {
                    _lastClickSecond = nowSecond;
                    _clicksThisSecond = 0;
                }
                _clicksThisSecond++;
                if (_clicksThisSecond > MaxClicksPerSecond)
                    return CallNextHookEx(_hookHandle, nCode, wParam, lParam);

                var hookStruct = Marshal.PtrToStructure<MSLLHOOKSTRUCT>(lParam);
                var now = DateTime.UtcNow;

                string button = msg switch
                {
                    WM_LBUTTONDOWN or WM_LBUTTONDBLCLK => "left",
                    WM_RBUTTONDOWN => "right",
                    WM_MBUTTONDOWN => "middle",
                    _ => "left"
                };

                // Double-click detection
                string clickType = "single";
                if (msg == WM_LBUTTONDBLCLK)
                {
                    clickType = "double";
                }
                else if (button == _lastClickButton &&
                         (now - _lastClickTime).TotalMilliseconds < DoubleClickThresholdMs &&
                         Math.Abs(hookStruct.X - _lastClickX) <= DoubleClickDistancePx &&
                         Math.Abs(hookStruct.Y - _lastClickY) <= DoubleClickDistancePx)
                {
                    clickType = "double";
                }

                _lastClickTime = now;
                _lastClickX = hookStruct.X;
                _lastClickY = hookStruct.Y;
                _lastClickButton = button;

                // Get foreground window context (fast, < 1ms)
                string processName = "";
                string windowTitle = "";
                try
                {
                    var hwnd = GetForegroundWindow();
                    if (hwnd != IntPtr.Zero)
                    {
                        GetWindowThreadProcessId(hwnd, out uint pid);
                        if (pid != 0)
                        {
                            try { processName = Process.GetProcessById((int)pid).ProcessName; } catch { }
                        }
                        var sb = new StringBuilder(256);
                        GetWindowText(hwnd, sb, sb.Capacity);
                        windowTitle = sb.ToString();
                    }
                }
                catch { }

                _pending.Enqueue(new InputEventData
                {
                    Id = Guid.NewGuid().ToString(),
                    EventType = "mouse_click",
                    EventTs = now.ToString("o"),
                    ClickX = hookStruct.X,
                    ClickY = hookStruct.Y,
                    ClickButton = button,
                    ClickType = clickType,
                    ProcessName = processName,
                    WindowTitle = windowTitle.Length > 200 ? windowTitle[..200] : windowTitle
                });
            }
        }
        return CallNextHookEx(_hookHandle, nCode, wParam, lParam);
    }

    private async Task SendPendingAsync()
    {
        if (_pending.IsEmpty) return;

        var batch = new List<InputEventData>();
        while (batch.Count < 500 && _pending.TryDequeue(out var item))
        {
            batch.Add(item);
        }

        if (batch.Count == 0) return;

        try
        {
            var response = await _pipe.SendAsync(new PipeRequest
            {
                Command = "report_input_events",
                InputEvents = batch
            });

            if (response?.Success == true)
            {
                _log.Debug($"Sent {batch.Count} input events to service");
            }
            else
            {
                _log.Warn($"Service rejected input events: {response?.Error}");
                // Re-queue on failure
                foreach (var item in batch)
                    _pending.Enqueue(item);
            }
        }
        catch (Exception ex)
        {
            _log.Warn($"Failed to send input events: {ex.Message}");
            foreach (var item in batch)
                _pending.Enqueue(item);
        }
    }

    public void Dispose()
    {
        _sendTimer?.Dispose();
        if (_hookHandle != IntPtr.Zero)
        {
            UnhookWindowsHookEx(_hookHandle);
            _hookHandle = IntPtr.Zero;
            _log.Info("Mouse hook removed");
        }
        _pipe.Dispose();
    }
}
