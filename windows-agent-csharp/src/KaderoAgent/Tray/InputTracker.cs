using System.Collections.Concurrent;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;
using KaderoAgent.Ipc;

namespace KaderoAgent.Tray;

/// <summary>
/// Tracks mouse clicks, scroll events, and keyboard activity in the interactive user session (tray process).
/// Uses low-level hooks (WH_MOUSE_LL + WH_KEYBOARD_LL) to capture input events.
/// Pending events are drained by TrayWindowTracker and sent via its pipe connection.
/// </summary>
public class InputTracker : IDisposable
{
    // Mouse hook constants
    private const int WH_MOUSE_LL = 14;
    private const int WM_LBUTTONDOWN = 0x0201;
    private const int WM_RBUTTONDOWN = 0x0204;
    private const int WM_MBUTTONDOWN = 0x0207;
    private const int WM_LBUTTONDBLCLK = 0x0203;
    private const int WM_MOUSEWHEEL = 0x020A;
    private const int WM_MOUSEHWHEEL = 0x020E;

    // Keyboard hook constants
    private const int WH_KEYBOARD_LL = 13;
    private const int WM_KEYDOWN = 0x0100;
    private const int WM_SYSKEYDOWN = 0x0104;

    // Limits
    private const int MaxClicksPerSecond = 100;
    private const int DoubleClickThresholdMs = 500;
    private const int DoubleClickDistancePx = 5;

    // Keyboard aggregation: emit metric every 10 seconds
    private const int KeyboardIntervalMs = 10_000;
    private const int TypingBurstThreshold = 10; // keys in 5 seconds = burst

    // Scroll aggregation: aggregate per 500ms window
    private const int ScrollAggregationMs = 500;

    private static readonly log4net.ILog _log = log4net.LogManager.GetLogger(typeof(InputTracker));

    // Hook delegates (prevent GC)
    private delegate IntPtr LowLevelProc(int nCode, IntPtr wParam, IntPtr lParam);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern IntPtr SetWindowsHookEx(int idHook, LowLevelProc lpfn, IntPtr hMod, uint dwThreadId);

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

    // Hook handles
    private IntPtr _mouseHookHandle = IntPtr.Zero;
    private IntPtr _keyboardHookHandle = IntPtr.Zero;
    private LowLevelProc? _mouseHookProc;
    private LowLevelProc? _keyboardHookProc;

    // Output queue
    private readonly ConcurrentQueue<InputEventData> _pending = new();

    // Segment context (updated from AgentStatus via TrayApplication status polling)
    private volatile string? _currentSegmentStartTs;

    // Mouse click state
    private int _clicksThisSecond;
    private long _lastClickSecond;
    private DateTime _lastClickTime;
    private int _lastClickX, _lastClickY;
    private string _lastClickButton = "";

    // Keyboard aggregation state
    private int _keystrokeCount;
    private DateTime _keyboardIntervalStart = DateTime.UtcNow;
    private string _keyboardProcessName = "";
    private string _keyboardWindowTitle = "";
    private System.Threading.Timer? _keyboardFlushTimer;

    // Typing burst detection: sliding window
    private readonly Queue<DateTime> _recentKeystrokes = new();

    // Scroll aggregation state
    private int _scrollDelta;
    private int _scrollEventCount;
    private string _scrollDirection = "down";
    private DateTime _scrollWindowStart;
    private string _scrollProcessName = "";
    private System.Threading.Timer? _scrollFlushTimer;

    public void Start()
    {
        using var curProcess = Process.GetCurrentProcess();
        using var curModule = curProcess.MainModule!;
        var moduleHandle = GetModuleHandle(curModule.ModuleName!);

        // Install mouse hook (clicks + scroll)
        _mouseHookProc = MouseHookCallback;
        _mouseHookHandle = SetWindowsHookEx(WH_MOUSE_LL, _mouseHookProc, moduleHandle, 0);
        if (_mouseHookHandle == IntPtr.Zero)
            _log.Error($"Mouse hook failed: {Marshal.GetLastWin32Error()}");
        else
            _log.Info("Mouse hook installed");

        // Install keyboard hook (keystroke counting)
        _keyboardHookProc = KeyboardHookCallback;
        _keyboardHookHandle = SetWindowsHookEx(WH_KEYBOARD_LL, _keyboardHookProc, moduleHandle, 0);
        if (_keyboardHookHandle == IntPtr.Zero)
            _log.Error($"Keyboard hook failed: {Marshal.GetLastWin32Error()}");
        else
            _log.Info("Keyboard hook installed");

        // Keyboard flush timer (every 10 seconds)
        _keyboardFlushTimer = new System.Threading.Timer(_ => FlushKeyboardMetric(), null, KeyboardIntervalMs, KeyboardIntervalMs);
    }

    /// <summary>Update segment context from AgentStatus (called by TrayApplication on status poll).</summary>
    public void UpdateSegmentContext(string? segmentStartTs)
    {
        _currentSegmentStartTs = segmentStartTs;
    }

    /// <summary>Drain all pending input events. Called by TrayWindowTracker.</summary>
    public List<InputEventData> DrainPending()
    {
        // Flush pending keyboard/scroll before drain
        FlushKeyboardMetric();
        FlushScrollWindow();

        var batch = new List<InputEventData>();
        while (batch.Count < 500 && _pending.TryDequeue(out var item))
            batch.Add(item);

        if (batch.Count > 0)
        {
            var types = batch.GroupBy(e => e.EventType).Select(g => $"{g.Key}={g.Count()}");
            _log.Info($"DrainPending: {batch.Count} events ({string.Join(", ", types)})");
        }
        return batch;
    }

    /// <summary>Re-queue events on send failure.</summary>
    public void Requeue(List<InputEventData> events)
    {
        foreach (var item in events)
            _pending.Enqueue(item);
    }

    // ── Mouse Hook ──────────────────────────────────────────────────

    private IntPtr MouseHookCallback(int nCode, IntPtr wParam, IntPtr lParam)
    {
        if (nCode >= 0)
        {
            int msg = wParam.ToInt32();

            // Mouse clicks
            if (msg == WM_LBUTTONDOWN || msg == WM_RBUTTONDOWN || msg == WM_MBUTTONDOWN || msg == WM_LBUTTONDBLCLK)
            {
                HandleMouseClick(msg, lParam);
            }
            // Scroll events
            else if (msg == WM_MOUSEWHEEL || msg == WM_MOUSEHWHEEL)
            {
                HandleScroll(msg, lParam);
            }
        }
        return CallNextHookEx(_mouseHookHandle, nCode, wParam, lParam);
    }

    private void HandleMouseClick(int msg, IntPtr lParam)
    {
        long nowSecond = Environment.TickCount64 / 1000;
        if (nowSecond != _lastClickSecond)
        {
            _lastClickSecond = nowSecond;
            _clicksThisSecond = 0;
        }
        if (++_clicksThisSecond > MaxClicksPerSecond) return;

        var hookStruct = Marshal.PtrToStructure<MSLLHOOKSTRUCT>(lParam);
        var now = DateTime.UtcNow;

        string button = msg switch
        {
            WM_LBUTTONDOWN or WM_LBUTTONDBLCLK => "left",
            WM_RBUTTONDOWN => "right",
            WM_MBUTTONDOWN => "middle",
            _ => "left"
        };

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

        var (processName, windowTitle) = GetForegroundContext();

        var clickEvent = new InputEventData
        {
            Id = Guid.NewGuid().ToString(),
            EventType = "mouse_click",
            EventTs = now.ToString("o"),
            ClickX = hookStruct.X,
            ClickY = hookStruct.Y,
            ClickButton = button,
            ClickType = clickType,
            ProcessName = processName,
            WindowTitle = windowTitle.Length > 200 ? windowTitle[..200] : windowTitle,
            SegmentOffsetMs = ComputeSegmentOffsetMs(now)
        };

        // Resolve UI element async (200ms timeout, doesn't block hook)
        var cx = hookStruct.X;
        var cy = hookStruct.Y;
        Task.Run(() =>
        {
            var elem = UIElementResolver.GetElementAt(cx, cy);
            if (elem != null)
            {
                clickEvent.UiElementType = elem.ElementType;
                clickEvent.UiElementName = elem.ElementName;
            }
            _pending.Enqueue(clickEvent);
        });
    }

    private void HandleScroll(int msg, IntPtr lParam)
    {
        var hookStruct = Marshal.PtrToStructure<MSLLHOOKSTRUCT>(lParam);
        // mouseData high word = wheel delta (120 = 1 notch)
        short delta = (short)(hookStruct.mouseData >> 16);
        bool isHorizontal = msg == WM_MOUSEHWHEEL;
        string direction = isHorizontal
            ? (delta > 0 ? "right" : "left")
            : (delta > 0 ? "up" : "down");

        var now = DateTime.UtcNow;

        // Aggregate within 500ms window
        if (_scrollEventCount > 0 && (now - _scrollWindowStart).TotalMilliseconds < ScrollAggregationMs
            && _scrollDirection == direction)
        {
            _scrollDelta += Math.Abs(delta);
            _scrollEventCount++;
        }
        else
        {
            // Flush previous window if any
            FlushScrollWindow();

            // Start new window
            _scrollWindowStart = now;
            _scrollDirection = direction;
            _scrollDelta = Math.Abs(delta);
            _scrollEventCount = 1;
            var (proc, _) = GetForegroundContext();
            _scrollProcessName = proc;

            // Set timer to flush after 500ms if no more scroll events
            _scrollFlushTimer?.Dispose();
            _scrollFlushTimer = new System.Threading.Timer(_ => FlushScrollWindow(), null, ScrollAggregationMs + 100, Timeout.Infinite);
        }
    }

    private void FlushScrollWindow()
    {
        if (_scrollEventCount == 0) return;

        var count = _scrollEventCount;
        var delta = _scrollDelta;
        var direction = _scrollDirection;
        var start = _scrollWindowStart;
        var proc = _scrollProcessName;

        _scrollEventCount = 0;
        _scrollDelta = 0;

        var end = start.AddMilliseconds(ScrollAggregationMs);

        _pending.Enqueue(new InputEventData
        {
            Id = Guid.NewGuid().ToString(),
            EventType = "scroll",
            EventTs = start.ToString("o"),
            EventEndTs = end.ToString("o"),
            ScrollDirection = direction,
            ScrollTotalDelta = delta,
            ScrollEventCount = count,
            ProcessName = proc,
            SegmentOffsetMs = ComputeSegmentOffsetMs(start)
        });
    }

    // ── Keyboard Hook ───────────────────────────────────────────────

    private IntPtr KeyboardHookCallback(int nCode, IntPtr wParam, IntPtr lParam)
    {
        if (nCode >= 0)
        {
            int msg = wParam.ToInt32();
            if (msg == WM_KEYDOWN || msg == WM_SYSKEYDOWN)
            {
                Interlocked.Increment(ref _keystrokeCount);

                // Track for burst detection
                lock (_recentKeystrokes)
                {
                    var now = DateTime.UtcNow;
                    _recentKeystrokes.Enqueue(now);
                    // Keep only last 5 seconds
                    while (_recentKeystrokes.Count > 0 && (now - _recentKeystrokes.Peek()).TotalSeconds > 5)
                        _recentKeystrokes.Dequeue();
                }

                // Capture context on first keystroke of interval
                if (Volatile.Read(ref _keystrokeCount) == 1)
                {
                    var (proc, title) = GetForegroundContext();
                    _keyboardProcessName = proc;
                    _keyboardWindowTitle = title.Length > 200 ? title[..200] : title;
                }
            }
        }
        return CallNextHookEx(_keyboardHookHandle, nCode, wParam, lParam);
    }

    private void FlushKeyboardMetric()
    {
        var count = Interlocked.Exchange(ref _keystrokeCount, 0);
        if (count == 0) return;

        var now = DateTime.UtcNow;
        var intervalStart = _keyboardIntervalStart;
        _keyboardIntervalStart = now;

        bool hasBurst;
        lock (_recentKeystrokes)
        {
            hasBurst = _recentKeystrokes.Count >= TypingBurstThreshold;
            _recentKeystrokes.Clear();
        }

        _pending.Enqueue(new InputEventData
        {
            Id = Guid.NewGuid().ToString(),
            EventType = "keyboard_metric",
            EventTs = intervalStart.ToString("o"),
            EventEndTs = now.ToString("o"),
            KeystrokeCount = count,
            HasTypingBurst = hasBurst,
            ProcessName = _keyboardProcessName,
            WindowTitle = _keyboardWindowTitle,
            SegmentOffsetMs = ComputeSegmentOffsetMs(intervalStart)
        });
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private int? ComputeSegmentOffsetMs(DateTime eventTs)
    {
        var startTs = _currentSegmentStartTs;
        if (startTs == null) return null;
        if (!DateTime.TryParse(startTs, null, System.Globalization.DateTimeStyles.RoundtripKind, out var segStart))
            return null;
        var offset = (int)(eventTs - segStart).TotalMilliseconds;
        return offset >= 0 ? offset : null;
    }

    private (string processName, string windowTitle) GetForegroundContext()
    {
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
        return (processName, windowTitle);
    }

    public void Dispose()
    {
        _keyboardFlushTimer?.Dispose();
        _scrollFlushTimer?.Dispose();

        // Flush remaining keyboard metric
        FlushKeyboardMetric();
        // Flush remaining scroll window
        FlushScrollWindow();

        if (_mouseHookHandle != IntPtr.Zero)
        {
            UnhookWindowsHookEx(_mouseHookHandle);
            _mouseHookHandle = IntPtr.Zero;
            _log.Info("Mouse hook removed");
        }
        if (_keyboardHookHandle != IntPtr.Zero)
        {
            UnhookWindowsHookEx(_keyboardHookHandle);
            _keyboardHookHandle = IntPtr.Zero;
            _log.Info("Keyboard hook removed");
        }
    }
}
