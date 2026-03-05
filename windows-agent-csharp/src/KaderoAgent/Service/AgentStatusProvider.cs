namespace KaderoAgent.Service;

using KaderoAgent.Auth;
using KaderoAgent.Capture;
using KaderoAgent.Configuration;
using KaderoAgent.Ipc;
using KaderoAgent.Upload;
using Microsoft.Extensions.Options;

public class AgentStatusProvider : IStatusProvider
{
    private readonly AuthManager _authManager;
    private readonly CredentialStore _credentialStore;
    private readonly ScreenCaptureManager _captureManager;
    private readonly UploadQueue _uploadQueue;
    private readonly MetricsCollector _metrics;
    private readonly IOptions<AgentConfig> _config;

    private string _connectionStatus = "disconnected";
    private string? _lastError;
    private DateTime? _lastHeartbeatTs;

    public AgentStatusProvider(AuthManager authManager, CredentialStore credentialStore,
        ScreenCaptureManager captureManager, UploadQueue uploadQueue,
        MetricsCollector metrics, IOptions<AgentConfig> config)
    {
        _authManager = authManager;
        _credentialStore = credentialStore;
        _captureManager = captureManager;
        _uploadQueue = uploadQueue;
        _metrics = metrics;
        _config = config;
    }

    public void SetConnectionStatus(string status) => _connectionStatus = status;
    public void SetLastError(string? error) => _lastError = error;
    public void SetLastHeartbeat(DateTime ts) => _lastHeartbeatTs = ts;

    public AgentStatus GetCurrentStatus()
    {
        var serverConfig = _authManager.ServerConfig;
        var creds = _credentialStore.Load();

        return new AgentStatus
        {
            ConnectionStatus = _authManager.IsAuthenticated ? _connectionStatus : "disconnected",
            RecordingStatus = _captureManager.IsRecording ? "recording" : "stopped",
            DeviceId = _authManager.DeviceId,
            ServerUrl = creds?.ServerUrl,
            CaptureFps = serverConfig?.CaptureFps ?? _config.Value.CaptureFps,
            Quality = serverConfig?.Quality ?? _config.Value.Quality,
            SegmentDurationSec = serverConfig?.SegmentDurationSec ?? _config.Value.SegmentDurationSec,
            HeartbeatIntervalSec = serverConfig?.HeartbeatIntervalSec ?? _config.Value.HeartbeatIntervalSec,
            CpuPercent = _metrics.GetCpuUsage(),
            MemoryMb = _metrics.GetMemoryUsageMb(),
            DiskFreeGb = _metrics.GetDiskFreeGb(),
            SegmentsQueued = _uploadQueue.QueuedCount,
            LastHeartbeatTs = _lastHeartbeatTs,
            LastError = _lastError
        };
    }
}
