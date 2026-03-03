using KaderoAgent.Auth;
using KaderoAgent.Capture;
using KaderoAgent.Configuration;
using KaderoAgent.Service;
using KaderoAgent.Upload;
using KaderoAgent.Util;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;

namespace KaderoAgent.Command;

public class HeartbeatService : BackgroundService
{
    private readonly AuthManager _authManager;
    private readonly ApiClient _apiClient;
    private readonly CredentialStore _credentialStore;
    private readonly CommandHandler _commandHandler;
    private readonly ScreenCaptureManager _captureManager;
    private readonly UploadQueue _uploadQueue;
    private readonly MetricsCollector _metrics;
    private readonly IOptions<AgentConfig> _config;
    private readonly ILogger<HeartbeatService> _logger;

    public HeartbeatService(AuthManager authManager, ApiClient apiClient, CredentialStore credentialStore,
        CommandHandler commandHandler, ScreenCaptureManager captureManager, UploadQueue uploadQueue,
        MetricsCollector metrics, IOptions<AgentConfig> config, ILogger<HeartbeatService> logger)
    {
        _authManager = authManager;
        _apiClient = apiClient;
        _credentialStore = credentialStore;
        _commandHandler = commandHandler;
        _captureManager = captureManager;
        _uploadQueue = uploadQueue;
        _metrics = metrics;
        _config = config;
        _logger = logger;
    }

    protected override async Task ExecuteAsync(CancellationToken ct)
    {
        // Wait for auth
        while (!_authManager.IsAuthenticated && !ct.IsCancellationRequested)
        {
            await Task.Delay(1000, ct);
        }

        var interval = _authManager.ServerConfig?.HeartbeatIntervalSec ?? _config.Value.HeartbeatIntervalSec;

        while (!ct.IsCancellationRequested)
        {
            try
            {
                await _authManager.EnsureValidTokenAsync();
                var creds = _credentialStore.Load();
                if (creds == null) { await Task.Delay(5000, ct); continue; }

                var baseUrl = creds.ServerUrl.TrimEnd('/');
                var url = $"{baseUrl}/api/cp/v1/devices/{_authManager.DeviceId}/heartbeat";

                var status = _captureManager.IsRecording ? "recording" : "online";
                var body = new
                {
                    status,
                    agent_version = "1.0.0",
                    metrics = new
                    {
                        cpu_percent = _metrics.GetCpuUsage(),
                        memory_mb = _metrics.GetMemoryUsageMb(),
                        disk_free_gb = _metrics.GetDiskFreeGb(),
                        segments_queued = _uploadQueue.QueuedCount
                    }
                };

                var response = await _apiClient.PutAsync<HeartbeatResponse>(url, body, ct);

                // Process pending commands
                if (response?.PendingCommands != null)
                {
                    foreach (var cmd in response.PendingCommands)
                    {
                        await _commandHandler.HandleAsync(cmd, baseUrl, ct);
                    }
                }

                interval = response?.NextHeartbeatSec ?? interval;
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "Heartbeat failed");
            }

            await Task.Delay(TimeSpan.FromSeconds(interval), ct);
        }
    }
}

public class HeartbeatResponse
{
    public string? ServerTs { get; set; }
    public List<PendingCommand>? PendingCommands { get; set; }
    public int NextHeartbeatSec { get; set; } = 30;
}

public class PendingCommand
{
    public string? Id { get; set; }
    public string? CommandType { get; set; }
    public Dictionary<string, object>? Payload { get; set; }
    public string? CreatedTs { get; set; }
}
