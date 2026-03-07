using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;
using KaderoAgent.Command;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace KaderoAgent.Audit;

public class ActiveWindowTracker : BackgroundService
{
    [DllImport("user32.dll")]
    private static extern IntPtr GetForegroundWindow();

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern int GetWindowText(IntPtr hWnd, StringBuilder sb, int maxCount);

    [DllImport("user32.dll")]
    private static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint lpdwProcessId);

    private const int PollIntervalMs = 5000;
    private const int MinIntervalMs = 3000;

    private readonly FocusIntervalSink _sink;
    private readonly CommandHandler _commandHandler;
    private readonly ILogger<ActiveWindowTracker> _logger;

    // State
    private IntPtr _lastHwnd;
    private string _lastProcessName = "";
    private string _lastWindowTitle = "";
    private DateTime _focusStartedAt;

    private static readonly HashSet<string> IgnoredProcesses = new(StringComparer.OrdinalIgnoreCase)
    {
        "KaderoAgent", "explorer", "Progman", "ShellExperienceHost",
        "Idle", "dwm", "csrss", "svchost", "winlogon"
    };

    public ActiveWindowTracker(
        FocusIntervalSink sink,
        CommandHandler commandHandler,
        ILogger<ActiveWindowTracker> logger)
    {
        _sink = sink;
        _commandHandler = commandHandler;
        _logger = logger;
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        _logger.LogInformation("ActiveWindowTracker started (poll interval: {Ms}ms)", PollIntervalMs);

        while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                Poll();
                await Task.Delay(PollIntervalMs, stoppingToken);
            }
            catch (OperationCanceledException) { break; }
            catch (Exception ex)
            {
                _logger.LogDebug(ex, "Error in ActiveWindowTracker poll loop");
                await Task.Delay(PollIntervalMs, stoppingToken);
            }
        }

        // Complete last interval on shutdown
        CompletePreviousInterval(DateTime.UtcNow);
        _logger.LogInformation("ActiveWindowTracker stopped");
    }

    private void Poll()
    {
        var hwnd = GetForegroundWindow();
        if (hwnd == IntPtr.Zero) return;

        // Same window -- skip
        if (hwnd == _lastHwnd) return;

        // Get process info
        GetWindowThreadProcessId(hwnd, out uint pid);
        string processName;
        try
        {
            processName = Process.GetProcessById((int)pid).ProcessName;
        }
        catch
        {
            return; // Process may have exited
        }

        if (IgnoredProcesses.Contains(processName)) return;

        // Get window title
        var sb = new StringBuilder(2048);
        GetWindowText(hwnd, sb, 2048);
        var windowTitle = sb.ToString().Trim();
        if (string.IsNullOrEmpty(windowTitle)) return;

        var now = DateTime.UtcNow;

        // Complete previous interval
        CompletePreviousInterval(now);

        // Start new interval
        _lastHwnd = hwnd;
        _lastProcessName = processName;
        _lastWindowTitle = windowTitle;
        _focusStartedAt = now;
    }

    private void CompletePreviousInterval(DateTime now)
    {
        if (string.IsNullOrEmpty(_lastProcessName)) return;

        var duration = (int)(now - _focusStartedAt).TotalMilliseconds;
        if (duration < MinIntervalMs) return;

        bool isBrowser = BrowserDomainParser.IsBrowser(_lastProcessName);
        string? browserName = null, domain = null;
        if (isBrowser)
        {
            (browserName, domain) = BrowserDomainParser.ParseTitle(_lastWindowTitle, _lastProcessName);
        }

        _sink.Enqueue(new FocusInterval
        {
            ProcessName = _lastProcessName + ".exe",
            WindowTitle = _lastWindowTitle,
            IsBrowser = isBrowser,
            BrowserName = browserName,
            Domain = domain,
            StartedAt = _focusStartedAt,
            EndedAt = now,
            DurationMs = duration,
            SessionId = _commandHandler.CurrentSessionId
        });

        _lastProcessName = "";
    }
}
