using System.Management;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace KaderoAgent.Audit;

public class ProcessWatcher : BackgroundService
{
    private readonly IAuditEventSink _sink;
    private readonly ILogger<ProcessWatcher> _logger;

    private ManagementEventWatcher? _startWatcher;
    private ManagementEventWatcher? _stopWatcher;

    // Track processes to debounce short-lived ones
    private readonly Dictionary<int, DateTime> _recentStarts = new();
    private readonly object _processLock = new();

    // Paths to exclude
    private static readonly HashSet<string> ExcludedPrefixes = new(StringComparer.OrdinalIgnoreCase)
    {
        @"C:\Windows\",
        @"C:\Program Files\dotnet\",
    };

    private static readonly HashSet<string> ExcludedNames = new(StringComparer.OrdinalIgnoreCase)
    {
        "svchost.exe", "csrss.exe", "lsass.exe", "services.exe", "smss.exe",
        "wininit.exe", "winlogon.exe", "dwm.exe", "conhost.exe", "dllhost.exe",
        "taskhost.exe", "taskhostw.exe", "sihost.exe", "fontdrvhost.exe",
        "WmiPrvSE.exe", "SearchIndexer.exe", "SecurityHealthService.exe",
        "MsMpEng.exe", "NisSrv.exe", "spoolsv.exe", "wuauserv.exe",
        "RuntimeBroker.exe", "backgroundTaskHost.exe", "ShellExperienceHost.exe",
        "StartMenuExperienceHost.exe", "SearchHost.exe", "TextInputHost.exe",
        "ctfmon.exe", "SystemSettings.exe", "ApplicationFrameHost.exe",
        "CompPkgSrv.exe", "KaderoAgent.exe"
    };

    public ProcessWatcher(IAuditEventSink sink, ILogger<ProcessWatcher> logger)
    {
        _sink = sink;
        _logger = logger;
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        try
        {
            // WMI query: poll every 2 seconds for new processes
            var startQuery = new WqlEventQuery(
                "SELECT * FROM __InstanceCreationEvent WITHIN 2 WHERE TargetInstance ISA 'Win32_Process'");
            _startWatcher = new ManagementEventWatcher(startQuery);
            _startWatcher.EventArrived += OnProcessStarted;
            _startWatcher.Start();

            var stopQuery = new WqlEventQuery(
                "SELECT * FROM __InstanceDeletionEvent WITHIN 2 WHERE TargetInstance ISA 'Win32_Process'");
            _stopWatcher = new ManagementEventWatcher(stopQuery);
            _stopWatcher.EventArrived += OnProcessStopped;
            _stopWatcher.Start();

            _logger.LogInformation("ProcessWatcher started (WMI)");

            await Task.Delay(Timeout.Infinite, stoppingToken);
        }
        catch (OperationCanceledException) { }
        catch (Exception ex)
        {
            _logger.LogError(ex, "ProcessWatcher failed to start");
        }
        finally
        {
            _startWatcher?.Stop();
            _startWatcher?.Dispose();
            _stopWatcher?.Stop();
            _stopWatcher?.Dispose();
            _logger.LogInformation("ProcessWatcher stopped");
        }
    }

    private void OnProcessStarted(object sender, EventArrivedEventArgs e)
    {
        try
        {
            var targetInstance = (ManagementBaseObject)e.NewEvent["TargetInstance"];
            var processName = targetInstance["Name"]?.ToString() ?? "";
            var pid = Convert.ToInt32(targetInstance["ProcessId"]);
            var execPath = targetInstance["ExecutablePath"]?.ToString() ?? "";
            var sessionId = Convert.ToInt32(targetInstance["SessionId"]);

            // Filter: skip session 0 (system services)
            if (sessionId == 0) return;

            // Filter: skip excluded names
            if (ExcludedNames.Contains(processName)) return;

            // Filter: skip excluded paths
            if (!string.IsNullOrEmpty(execPath) && ExcludedPrefixes.Any(p => execPath.StartsWith(p, StringComparison.OrdinalIgnoreCase)))
                return;

            // Track for debounce
            lock (_processLock)
            {
                _recentStarts[pid] = DateTime.UtcNow;
            }

            // Delay to debounce short-lived processes
            Task.Delay(3000).ContinueWith(_ =>
            {
                bool shouldPublish;
                lock (_processLock)
                {
                    shouldPublish = _recentStarts.Remove(pid);
                }

                if (shouldPublish)
                {
                    _sink.Publish(new AuditEvent
                    {
                        EventType = "PROCESS_START",
                        Details = new Dictionary<string, object>
                        {
                            ["process_name"] = processName,
                            ["pid"] = pid,
                            ["exe_path"] = execPath
                        }
                    });
                }
            });
        }
        catch (Exception ex)
        {
            _logger.LogDebug(ex, "Error processing start event");
        }
    }

    private void OnProcessStopped(object sender, EventArrivedEventArgs e)
    {
        try
        {
            var targetInstance = (ManagementBaseObject)e.NewEvent["TargetInstance"];
            var processName = targetInstance["Name"]?.ToString() ?? "";
            var pid = Convert.ToInt32(targetInstance["ProcessId"]);
            var execPath = targetInstance["ExecutablePath"]?.ToString() ?? "";
            var sessionId = Convert.ToInt32(targetInstance["SessionId"]);

            if (sessionId == 0) return;
            if (ExcludedNames.Contains(processName)) return;
            if (!string.IsNullOrEmpty(execPath) && ExcludedPrefixes.Any(p => execPath.StartsWith(p, StringComparison.OrdinalIgnoreCase)))
                return;

            // Cancel debounce if process stopped quickly (short-lived)
            lock (_processLock)
            {
                if (_recentStarts.TryGetValue(pid, out var startTime))
                {
                    if ((DateTime.UtcNow - startTime).TotalSeconds < 3)
                    {
                        _recentStarts.Remove(pid);
                        return; // Skip short-lived process
                    }
                }
            }

            _sink.Publish(new AuditEvent
            {
                EventType = "PROCESS_STOP",
                Details = new Dictionary<string, object>
                {
                    ["process_name"] = processName,
                    ["pid"] = pid,
                    ["exe_path"] = execPath
                }
            });
        }
        catch (Exception ex)
        {
            _logger.LogDebug(ex, "Error processing stop event");
        }
    }
}
