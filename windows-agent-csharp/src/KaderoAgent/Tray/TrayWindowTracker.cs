using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;
using KaderoAgent.Audit;
using KaderoAgent.Ipc;

namespace KaderoAgent.Tray;

/// <summary>
/// Polls the foreground window from the interactive user session (tray process).
/// GetForegroundWindow() works correctly here — unlike in Windows Service (Session 0).
/// Sends collected focus intervals to the service via Named Pipe every 30 seconds.
/// Uses its own PipeClient to avoid contention with the status polling pipe.
/// </summary>
public class TrayWindowTracker : IDisposable
{
    [DllImport("user32.dll")]
    private static extern IntPtr GetForegroundWindow();

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern int GetWindowText(IntPtr hWnd, StringBuilder sb, int maxCount);

    [DllImport("user32.dll")]
    private static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint lpdwProcessId);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetWindowRect(IntPtr hWnd, out RECT lpRect);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool IsZoomed(IntPtr hWnd);

    [DllImport("user32.dll")]
    private static extern IntPtr MonitorFromWindow(IntPtr hwnd, uint dwFlags);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetMonitorInfo(IntPtr hMonitor, ref MONITORINFO lpmi);

    private const uint MONITOR_DEFAULTTONEAREST = 2;

    [StructLayout(LayoutKind.Sequential)]
    private struct RECT { public int Left, Top, Right, Bottom; }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct MONITORINFO
    {
        public int cbSize;
        public RECT rcMonitor;
        public RECT rcWork;
        public uint dwFlags;
    }

    private const int PollIntervalMs  = 5_000;
    private const int MinIntervalMs   = 3_000;
    private const int SendIntervalMs  = 30_000;
    private const int FlushIntervalMs = 60_000; // flush long-running intervals every 60s

    private static readonly log4net.ILog _log = log4net.LogManager.GetLogger(typeof(TrayWindowTracker));

    private readonly PipeClient _pipe = new();
    private InputTracker? _inputTracker;
    private System.Threading.Timer? _pollTimer;
    private System.Threading.Timer? _sendTimer;

    // Tracking state (only accessed from poll timer — single-threaded)
    private IntPtr _lastHwnd;
    private string _lastProcessName = "";
    private string _lastWindowTitle = "";
    private DateTime _focusStartedAt;

    // Browser domain captured at interval START (not end) to avoid tab-switch lag
    private string? _lastBrowserName;
    private string? _lastDomain;

    // Window geometry captured at interval START
    private int _lastWinX, _lastWinY, _lastWinW, _lastWinH;
    private bool _lastIsMaximized, _lastIsFullscreen;
    private int _lastMonitorIndex;

    // Pending intervals (shared between poll and send timers — use lock)
    private readonly List<FocusIntervalData> _pending = new();
    private readonly object _pendingLock = new();

    private static readonly HashSet<string> IgnoredProcesses = new(StringComparer.OrdinalIgnoreCase)
    {
        "KaderoAgent", "explorer", "Progman", "ShellExperienceHost",
        "Idle", "dwm", "csrss", "svchost", "winlogon"
    };

    /// <summary>Attach InputTracker so its events are sent via this tracker's pipe connection.</summary>
    public void SetInputTracker(InputTracker tracker) => _inputTracker = tracker;

    public void Start()
    {
        _pollTimer = new System.Threading.Timer(_ => Poll(), null, 1_000, PollIntervalMs);
        _sendTimer = new System.Threading.Timer(_ => SendIntervals(), null, SendIntervalMs, SendIntervalMs);
        _log.Info("TrayWindowTracker started");
    }

    // ── Poll (every 5 s) ───────────────────────────────────────────────────────

    private void Poll()
    {
        try
        {
            var hwnd = GetForegroundWindow();
            if (hwnd == IntPtr.Zero) return;

            GetWindowThreadProcessId(hwnd, out uint pid);
            string processName;
            try { processName = Process.GetProcessById((int)pid).ProcessName; }
            catch { return; } // process may have exited

            if (IgnoredProcesses.Contains(processName)) return;

            var sb = new StringBuilder(2048);
            GetWindowText(hwnd, sb, 2048);
            var title = sb.ToString().Trim();
            if (string.IsNullOrEmpty(title)) return;

            // Same window AND same title — nothing changed (no tab switch, no navigation)
            if (hwnd == _lastHwnd && title == _lastWindowTitle)
            {
                // Flush long-running intervals so analytics updates in real time
                FlushIfLongRunning();
                return;
            }

            var now = DateTime.UtcNow;

            // Complete the previous interval using domain captured at its START
            CompletePreviousInterval(now);

            // Start new interval
            _lastHwnd        = hwnd;
            _lastProcessName = processName;
            _lastWindowTitle = title;
            _focusStartedAt  = now;

            // Capture window geometry at interval start
            CaptureWindowGeometry(hwnd);

            // Capture browser domain NOW (at interval start) so it's correct
            _lastBrowserName = null;
            _lastDomain = null;
            if (BrowserDomainParser.IsBrowser(processName))
            {
                CaptureBrowserDomain(hwnd, title, processName);
            }
        }
        catch (Exception ex)
        {
            _log.Debug($"TrayWindowTracker.Poll error: {ex.Message}");
        }
    }

    /// <summary>
    /// Capture browser domain at interval START via UI Automation + title parser.
    /// </summary>
    private void CaptureWindowGeometry(IntPtr hwnd)
    {
        try
        {
            if (GetWindowRect(hwnd, out var rect))
            {
                _lastWinX = rect.Left;
                _lastWinY = rect.Top;
                _lastWinW = rect.Right - rect.Left;
                _lastWinH = rect.Bottom - rect.Top;
            }
            _lastIsMaximized = IsZoomed(hwnd);

            // Check fullscreen: window rect == monitor rect
            var hMonitor = MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST);
            if (hMonitor != IntPtr.Zero)
            {
                var mi = new MONITORINFO { cbSize = Marshal.SizeOf<MONITORINFO>() };
                if (GetMonitorInfo(hMonitor, ref mi))
                {
                    _lastIsFullscreen = _lastWinX <= mi.rcMonitor.Left && _lastWinY <= mi.rcMonitor.Top
                        && _lastWinX + _lastWinW >= mi.rcMonitor.Right && _lastWinY + _lastWinH >= mi.rcMonitor.Bottom;
                    // Monitor index: use pointer as simple index (0-based not available directly)
                    _lastMonitorIndex = 0; // TODO: enumerate monitors for proper index
                }
            }
        }
        catch { }
    }

    private void CaptureBrowserDomain(IntPtr hwnd, string title, string processName)
    {
        // Invalidate URL cache on every tab/window change so stale URLs aren't returned
        BrowserUrlExtractor.InvalidateCache();

        // Strategy 1: UI Automation — read URL from browser address bar
        try
        {
            var url = BrowserUrlExtractor.GetUrl(hwnd);
            if (!string.IsNullOrEmpty(url))
            {
                _lastDomain = BrowserUrlExtractor.ExtractDomainFromUrl(url);
                _log.Info($"BrowserUrl: url='{url}' → domain='{_lastDomain}' (hwnd={hwnd})");
            }
            else
            {
                _log.Info($"BrowserUrl: no URL from UI Automation (hwnd={hwnd}, title='{title}')");
            }
        }
        catch (Exception ex)
        {
            _log.Warn($"UI Automation URL extraction failed: {ex.Message}");
        }

        // Strategy 2: Parse window title (regex + known mappings)
        var (parsedBrowser, parsedDomain) = BrowserDomainParser.ParseTitle(title, processName);
        _lastBrowserName = parsedBrowser;
        if (_lastDomain == null && parsedDomain != null)
        {
            _lastDomain = parsedDomain;
            _log.Info($"Domain from title parser: '{_lastDomain}' (title='{title}')");
        }

        if (_lastDomain == null)
            _log.Warn($"Browser domain NOT resolved: process={processName}, title='{title}'");
    }

    /// <summary>
    /// If the current interval has been running longer than FlushIntervalMs,
    /// emit a completed interval for the elapsed time and start a new one.
    /// This ensures long stays on a single page are reported incrementally.
    /// </summary>
    private void FlushIfLongRunning()
    {
        if (string.IsNullOrEmpty(_lastProcessName)) return;

        var now = DateTime.UtcNow;
        var durationMs = (int)(now - _focusStartedAt).TotalMilliseconds;
        if (durationMs < FlushIntervalMs) return;

        // Refresh geometry for current window
        if (_lastHwnd != IntPtr.Zero)
            CaptureWindowGeometry(_lastHwnd);

        bool isBrowser = BrowserDomainParser.IsBrowser(_lastProcessName);

        var interval = new FocusIntervalData
        {
            Id           = Guid.NewGuid().ToString(),
            ProcessName  = _lastProcessName + ".exe",
            WindowTitle  = _lastWindowTitle,
            IsBrowser    = isBrowser,
            BrowserName  = isBrowser ? _lastBrowserName : null,
            Domain       = isBrowser ? _lastDomain : null,
            StartedAt    = _focusStartedAt.ToString("o"),
            EndedAt      = now.ToString("o"),
            DurationMs   = durationMs,
            WindowX      = _lastWinX,
            WindowY      = _lastWinY,
            WindowWidth  = _lastWinW,
            WindowHeight = _lastWinH,
            IsMaximized  = _lastIsMaximized,
            IsFullscreen = _lastIsFullscreen,
            MonitorIndex = _lastMonitorIndex
        };

        lock (_pendingLock) { _pending.Add(interval); }

        // Reset start time for the next chunk (same window continues)
        _focusStartedAt = now;
    }

    private void CompletePreviousInterval(DateTime now)
    {
        if (string.IsNullOrEmpty(_lastProcessName)) return;

        var durationMs = (int)(now - _focusStartedAt).TotalMilliseconds;
        if (durationMs < MinIntervalMs) return;

        bool isBrowser = BrowserDomainParser.IsBrowser(_lastProcessName);

        var interval = new FocusIntervalData
        {
            Id           = Guid.NewGuid().ToString(),
            ProcessName  = _lastProcessName + ".exe",
            WindowTitle  = _lastWindowTitle,
            IsBrowser    = isBrowser,
            BrowserName  = isBrowser ? _lastBrowserName : null,
            Domain       = isBrowser ? _lastDomain : null,
            StartedAt    = _focusStartedAt.ToString("o"),
            EndedAt      = now.ToString("o"),
            DurationMs   = durationMs,
            WindowX      = _lastWinX,
            WindowY      = _lastWinY,
            WindowWidth  = _lastWinW,
            WindowHeight = _lastWinH,
            IsMaximized  = _lastIsMaximized,
            IsFullscreen = _lastIsFullscreen,
            MonitorIndex = _lastMonitorIndex
        };

        lock (_pendingLock) { _pending.Add(interval); }
        _lastProcessName = "";
    }

    // ── Send (every 30 s) ─────────────────────────────────────────────────────

    private void SendIntervals()
    {
        // Send focus intervals if any
        List<FocusIntervalData> batch;
        lock (_pendingLock)
        {
            batch = new List<FocusIntervalData>(_pending);
            _pending.Clear();
        }

        if (batch.Count > 0)
        {
            try
            {
                if (!_pipe.IsConnected)
                    _pipe.ConnectAsync(1000).GetAwaiter().GetResult();

                var request = new PipeRequest
                {
                    Command        = "report_focus_intervals",
                    FocusIntervals = batch
                };

                var response = _pipe.SendAsync(request).GetAwaiter().GetResult();
                if (response?.Success == true)
                {
                    _log.Info($"TrayWindowTracker: sent {batch.Count} focus intervals to service");
                }
                else
                {
                    _log.Warn($"TrayWindowTracker: service rejected intervals: {response?.Error}. Re-queuing.");
                    lock (_pendingLock) { _pending.InsertRange(0, batch); }
                }
            }
            catch (Exception ex)
            {
                _log.Warn($"TrayWindowTracker.SendIntervals error: {ex.Message}. Re-queuing {batch.Count} intervals.");
                lock (_pendingLock) { _pending.InsertRange(0, batch); }
            }
        }

        // Always send input events (independent of focus intervals)
        SendInputEvents();
    }

    private void SendInputEvents()
    {
        if (_inputTracker == null) return;

        var inputBatch = _inputTracker.DrainPending();
        _log.Debug($"SendInputEvents: drained {inputBatch.Count} events from InputTracker");
        if (inputBatch.Count == 0) return;

        try
        {
            if (!_pipe.IsConnected)
                _pipe.ConnectAsync(1000).GetAwaiter().GetResult();

            var request = new PipeRequest
            {
                Command = "report_input_events",
                InputEvents = inputBatch
            };

            var response = _pipe.SendAsync(request).GetAwaiter().GetResult();
            if (response?.Success == true)
            {
                _log.Info($"TrayWindowTracker: sent {inputBatch.Count} input events to service");
            }
            else
            {
                _log.Warn($"TrayWindowTracker: service rejected input events: {response?.Error}. Re-queuing.");
                _inputTracker.Requeue(inputBatch);
            }
        }
        catch (Exception ex)
        {
            _log.Warn($"TrayWindowTracker.SendInputEvents error: {ex.Message}. Re-queuing {inputBatch.Count} events.");
            _inputTracker.Requeue(inputBatch);
        }
    }

    public void Dispose()
    {
        _pollTimer?.Dispose();
        _sendTimer?.Dispose();
        _pipe.Dispose();
    }
}
