namespace KaderoAgent.Service;

using KaderoAgent.Audit;
using KaderoAgent.Auth;
using KaderoAgent.Capture;
using KaderoAgent.Command;
using KaderoAgent.Configuration;
using KaderoAgent.Ipc;
using KaderoAgent.Storage;
using KaderoAgent.Upload;
using Microsoft.Extensions.Options;

public class AgentStatusProvider : IStatusProvider
{
    private readonly AuthManager _authManager;
    private readonly CredentialStore _credentialStore;
    private readonly ScreenCaptureManager _captureManager;
    private readonly LocalDatabase _db;
    private readonly SessionManager _sessionManager;
    private readonly MetricsCollector _metrics;
    private readonly SessionWatcher _sessionWatcher;
    private readonly IAuditEventSink _auditSink;
    private readonly IOptions<AgentConfig> _config;

    // Lazy-resolved to avoid circular DI
    private readonly IServiceProvider _serviceProvider;
    private AgentService? _agentService;
    private CommandHandler? _commandHandler;

    private readonly object _lock = new();
    private string _connectionStatus = "disconnected";
    private string? _lastError;
    private DateTime? _lastHeartbeatTs;

    public AgentStatusProvider(AuthManager authManager, CredentialStore credentialStore,
        ScreenCaptureManager captureManager, LocalDatabase db,
        SessionManager sessionManager, MetricsCollector metrics,
        SessionWatcher sessionWatcher, IAuditEventSink auditSink,
        IOptions<AgentConfig> config, IServiceProvider serviceProvider)
    {
        _authManager = authManager;
        _credentialStore = credentialStore;
        _captureManager = captureManager;
        _db = db;
        _sessionManager = sessionManager;
        _metrics = metrics;
        _sessionWatcher = sessionWatcher;
        _auditSink = auditSink;
        _config = config;
        _serviceProvider = serviceProvider;
    }

    private AgentService? GetAgentService()
    {
        if (_agentService == null)
        {
            try { _agentService = _serviceProvider.GetService(typeof(AgentService)) as AgentService; }
            catch { /* non-fatal */ }
        }
        return _agentService;
    }

    private CommandHandler? GetCommandHandler()
    {
        if (_commandHandler == null)
        {
            try { _commandHandler = _serviceProvider.GetService(typeof(CommandHandler)) as CommandHandler; }
            catch { /* non-fatal */ }
        }
        return _commandHandler;
    }

    public void SetConnectionStatus(string status) { lock (_lock) _connectionStatus = status; }
    public void SetLastError(string? error) { lock (_lock) _lastError = error; }
    public void SetLastHeartbeat(DateTime ts) { lock (_lock) _lastHeartbeatTs = ts; }

    public AgentStatus GetCurrentStatus()
    {
        var serverConfig = _authManager.ServerConfig;
        var creds = _credentialStore.Load();

        string connStatus;
        string? lastError;
        DateTime? lastHb;
        lock (_lock)
        {
            connStatus = _connectionStatus;
            lastError = _lastError;
            lastHb = _lastHeartbeatTs;
        }

        var agentState = GetAgentService()?.CurrentState ?? AgentState.Starting;

        // Map AgentState to recording status for backward compat with tray
        var recordingStatus = agentState switch
        {
            AgentState.Recording => "recording",
            AgentState.Configuring or AgentState.Starting => "starting",
            _ => "stopped"
        };

        var status = new AgentStatus
        {
            ConnectionStatus = _authManager.IsAuthenticated ? connStatus : "disconnected",
            RecordingStatus = recordingStatus,
            AgentStateDisplay = agentState.ToDisplayMessage(),
            AgentStateName = agentState.ToUiStateName(),
            DeviceId = _authManager.DeviceId,
            ServerUrl = creds?.ServerUrl,
            CaptureFps = serverConfig?.CaptureFps > 0 ? serverConfig.CaptureFps : _config.Value.CaptureFps,
            Quality = !string.IsNullOrEmpty(serverConfig?.Quality) ? serverConfig.Quality : _config.Value.Quality,
            SegmentDurationSec = serverConfig?.SegmentDurationSec > 0 ? serverConfig.SegmentDurationSec : _config.Value.SegmentDurationSec,
            HeartbeatIntervalSec = serverConfig?.HeartbeatIntervalSec > 0 ? serverConfig.HeartbeatIntervalSec : _config.Value.HeartbeatIntervalSec,
            Resolution = !string.IsNullOrEmpty(serverConfig?.Resolution) ? serverConfig.Resolution : _config.Value.Resolution,
            SessionMaxDurationHours = serverConfig?.SessionMaxDurationHours ?? _config.Value.SessionMaxDurationHours,
            SessionMaxDurationMin = serverConfig?.GetEffectiveSessionMaxDurationMin(_config.Value.SessionMaxDurationMin)
                                    ?? _config.Value.SessionMaxDurationMin,
            AutoStart = serverConfig?.AutoStart ?? _config.Value.AutoStart,
            CpuPercent = _metrics.GetCpuUsage(),
            MemoryMb = _metrics.GetMemoryUsageMb(),
            DiskFreeGb = _metrics.GetDiskFreeGb(),
            SegmentsQueued = GetPendingCount(_db.GetSegmentCountsByStatus()),
            SessionLocked = !_sessionWatcher.IsSessionActive,
            AuditEventsQueued = _auditSink.QueuedCount,
            UploadError = _sessionManager.HasSessionError,
            UploadErrorMessage = _sessionManager.HasSessionError ? "Сессия потеряна, пересоздание..." : null,
            FocusIntervalsQueued = GetPendingCount(_db.GetActivityCountsByStatus()),
            InputEventsQueued = 0,
            LastHeartbeatTs = lastHb,
            LastError = lastError
        };

        // Segment context for video timecode binding
        var cmdHandler = GetCommandHandler();
        if (cmdHandler?.LastSegmentStartTs > DateTime.MinValue)
        {
            status.SegmentStartTs = cmdHandler.LastSegmentStartTs.ToString("o");
        }

        return status;
    }

    private static int GetPendingCount(Dictionary<string, int> counts)
    {
        int total = 0;
        if (counts.TryGetValue("NEW", out var n)) total += n;
        if (counts.TryGetValue("PENDING", out var p)) total += p;
        if (counts.TryGetValue("QUEUED", out var q)) total += q;
        return total;
    }
}
