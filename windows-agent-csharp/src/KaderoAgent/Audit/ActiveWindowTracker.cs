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

    [DllImport("user32.dll")]
    private static extern bool GetLastInputInfo(ref LASTINPUTINFO plii);

    [StructLayout(LayoutKind.Sequential)]
    private struct LASTINPUTINFO
    {
        public uint cbSize;
        public uint dwTime;
    }

    private const int PollIntervalMs = 5000;
    private const int MinIntervalMs = 3000;
    private const int MaxIntervalMs = 60000; // Split intervals every 60s for accurate idle tracking

    private readonly FocusIntervalSink _sink;
    private readonly CommandHandler _commandHandler;
    private readonly ILogger<ActiveWindowTracker> _logger;

    // State
    private IntPtr _lastHwnd;
    private string _lastProcessName = "";
    private string _lastWindowTitle = "";
    private DateTime _focusStartedAt;

    // Idle tracking: did the user provide any input during the current focus interval?
    private bool _hadInputDuringInterval;
    private uint _lastInputTick;

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

        // Initialize last input tick
        _lastInputTick = GetCurrentInputTick();

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
        // Check for user input activity
        CheckInputActivity();

        var now = DateTime.UtcNow;

        // Split long intervals to capture idle periods within the same window
        if (!string.IsNullOrEmpty(_lastProcessName) &&
            (now - _focusStartedAt).TotalMilliseconds >= MaxIntervalMs)
        {
            // Complete current chunk and start a new one for the same window
            CompletePreviousInterval(now);
            _lastProcessName = GetProcessNameForHwnd(_lastHwnd);
            if (!string.IsNullOrEmpty(_lastProcessName))
            {
                var sb = new StringBuilder(2048);
                GetWindowText(_lastHwnd, sb, 2048);
                _lastWindowTitle = sb.ToString().Trim();
                _focusStartedAt = now;
                _hadInputDuringInterval = false;
                _lastInputTick = GetCurrentInputTick();
            }
            return;
        }

        var hwnd = GetForegroundWindow();
        if (hwnd == IntPtr.Zero) return;

        // Same window -- skip
        if (hwnd == _lastHwnd) return;

        // Get process info
        string processName = GetProcessNameForHwnd(hwnd);
        if (string.IsNullOrEmpty(processName)) return;
        if (IgnoredProcesses.Contains(processName)) return;

        // Get window title
        var titleSb = new StringBuilder(2048);
        GetWindowText(hwnd, titleSb, 2048);
        var windowTitle = titleSb.ToString().Trim();
        if (string.IsNullOrEmpty(windowTitle)) return;

        // Complete previous interval
        CompletePreviousInterval(now);

        // Start new interval
        _lastHwnd = hwnd;
        _lastProcessName = processName;
        _lastWindowTitle = windowTitle;
        _focusStartedAt = now;
        _hadInputDuringInterval = false;
        _lastInputTick = GetCurrentInputTick();
    }

    private void CompletePreviousInterval(DateTime now)
    {
        if (string.IsNullOrEmpty(_lastProcessName)) return;

        var duration = (int)(now - _focusStartedAt).TotalMilliseconds;
        if (duration < MinIntervalMs) return;

        // Final input check before completing
        CheckInputActivity();

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
            SessionId = _commandHandler.CurrentSessionId,
            IsIdle = !_hadInputDuringInterval
        });

        _lastProcessName = "";
    }

    /// <summary>
    /// Check if user input occurred since last check using GetLastInputInfo.
    /// </summary>
    private void CheckInputActivity()
    {
        uint currentTick = GetCurrentInputTick();
        if (currentTick != _lastInputTick)
        {
            _hadInputDuringInterval = true;
            _lastInputTick = currentTick;
        }
    }

    private static uint GetCurrentInputTick()
    {
        var info = new LASTINPUTINFO { cbSize = (uint)Marshal.SizeOf<LASTINPUTINFO>() };
        return GetLastInputInfo(ref info) ? info.dwTime : 0;
    }

    private static string GetProcessNameForHwnd(IntPtr hwnd)
    {
        if (hwnd == IntPtr.Zero) return "";
        GetWindowThreadProcessId(hwnd, out uint pid);
        try
        {
            return Process.GetProcessById((int)pid).ProcessName;
        }
        catch
        {
            return "";
        }
    }
}
