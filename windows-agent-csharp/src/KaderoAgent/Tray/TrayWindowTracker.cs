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

    private const int PollIntervalMs  = 5_000;
    private const int MinIntervalMs   = 3_000;
    private const int SendIntervalMs  = 30_000;

    private static readonly log4net.ILog _log = log4net.LogManager.GetLogger(typeof(TrayWindowTracker));

    private readonly PipeClient _pipe = new();
    private System.Threading.Timer? _pollTimer;
    private System.Threading.Timer? _sendTimer;

    // Tracking state (only accessed from poll timer — single-threaded)
    private IntPtr _lastHwnd;
    private string _lastProcessName = "";
    private string _lastWindowTitle = "";
    private DateTime _focusStartedAt;

    // Pending intervals (shared between poll and send timers — use lock)
    private readonly List<FocusIntervalData> _pending = new();
    private readonly object _pendingLock = new();

    private static readonly HashSet<string> IgnoredProcesses = new(StringComparer.OrdinalIgnoreCase)
    {
        "KaderoAgent", "explorer", "Progman", "ShellExperienceHost",
        "Idle", "dwm", "csrss", "svchost", "winlogon"
    };

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
            if (hwnd == _lastHwnd) return;

            GetWindowThreadProcessId(hwnd, out uint pid);
            string processName;
            try { processName = Process.GetProcessById((int)pid).ProcessName; }
            catch { return; } // process may have exited

            if (IgnoredProcesses.Contains(processName)) return;

            var sb = new StringBuilder(2048);
            GetWindowText(hwnd, sb, 2048);
            var title = sb.ToString().Trim();
            if (string.IsNullOrEmpty(title)) return;

            var now = DateTime.UtcNow;
            CompletePreviousInterval(now);

            _lastHwnd        = hwnd;
            _lastProcessName = processName;
            _lastWindowTitle = title;
            _focusStartedAt  = now;
        }
        catch (Exception ex)
        {
            _log.Debug($"TrayWindowTracker.Poll error: {ex.Message}");
        }
    }

    private void CompletePreviousInterval(DateTime now)
    {
        if (string.IsNullOrEmpty(_lastProcessName)) return;

        var durationMs = (int)(now - _focusStartedAt).TotalMilliseconds;
        if (durationMs < MinIntervalMs) return;

        bool isBrowser = BrowserDomainParser.IsBrowser(_lastProcessName);
        string? browserName = null, domain = null;
        if (isBrowser)
            (browserName, domain) = BrowserDomainParser.ParseTitle(_lastWindowTitle, _lastProcessName);

        var interval = new FocusIntervalData
        {
            Id           = Guid.NewGuid().ToString(),
            ProcessName  = _lastProcessName + ".exe",
            WindowTitle  = _lastWindowTitle,
            IsBrowser    = isBrowser,
            BrowserName  = browserName,
            Domain       = domain,
            StartedAt    = _focusStartedAt.ToString("o"),
            EndedAt      = now.ToString("o"),
            DurationMs   = durationMs
        };

        lock (_pendingLock) { _pending.Add(interval); }
        _lastProcessName = "";
    }

    // ── Send (every 30 s) ─────────────────────────────────────────────────────

    private void SendIntervals()
    {
        List<FocusIntervalData> batch;
        lock (_pendingLock)
        {
            if (_pending.Count == 0) return;
            batch = new List<FocusIntervalData>(_pending);
            _pending.Clear();
        }

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

    public void Dispose()
    {
        _pollTimer?.Dispose();
        _sendTimer?.Dispose();
        _pipe.Dispose();
    }
}
