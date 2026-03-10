using System.Text.Json;
using KaderoAgent.Auth;
using KaderoAgent.Capture;
using KaderoAgent.Configuration;
using KaderoAgent.Ipc;
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
    private readonly IStatusProvider _statusProvider;
    private readonly AgentService _agentService;
    private readonly IOptions<AgentConfig> _config;
    private readonly ILogger<HeartbeatService> _logger;

    public HeartbeatService(AuthManager authManager, ApiClient apiClient, CredentialStore credentialStore,
        CommandHandler commandHandler, ScreenCaptureManager captureManager, UploadQueue uploadQueue,
        MetricsCollector metrics, IStatusProvider statusProvider, AgentService agentService,
        IOptions<AgentConfig> config, ILogger<HeartbeatService> logger)
    {
        _authManager = authManager;
        _apiClient = apiClient;
        _credentialStore = credentialStore;
        _commandHandler = commandHandler;
        _captureManager = captureManager;
        _uploadQueue = uploadQueue;
        _metrics = metrics;
        _statusProvider = statusProvider;
        _agentService = agentService;
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

                // Use AgentState for heartbeat status instead of computing from flags
                var status = _agentService.CurrentState.ToHeartbeatStatus();
                var body = new
                {
                    status,
                    session_locked = _commandHandler.IsPausedByLock,
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

                // Update status provider after successful heartbeat
                if (_statusProvider is AgentStatusProvider asp)
                {
                    asp.SetConnectionStatus("connected");
                    asp.SetLastHeartbeat(DateTime.UtcNow);
                    asp.SetLastError(null);
                }

                // Apply device_settings from heartbeat response
                if (response?.DeviceSettings is { Count: > 0 } ds)
                {
                    var oldFps = _authManager.ServerConfig?.CaptureFps ?? 0;
                    ApplyDeviceSettings(ds);
                    var newFps = _authManager.ServerConfig?.CaptureFps ?? 0;

                    // Hot-restart recording if FPS changed while actively recording
                    if (oldFps != newFps && newFps > 0 && _captureManager.IsRecording)
                    {
                        _logger.LogInformation("FPS changed {OldFps} -> {NewFps} via heartbeat, restarting recording",
                            oldFps, newFps);
                        await _commandHandler.RestartRecordingAsync(baseUrl, ct);
                    }

                    // Stop recording if recording_enabled was turned off via heartbeat
                    if (_authManager.ServerConfig is { RecordingEnabled: false } && _captureManager.IsRecording)
                    {
                        _logger.LogInformation("recording_enabled=false from heartbeat, stopping recording");
                        await _commandHandler.StopRecordingExternalAsync(ct);
                        _agentService.SetState(AgentState.Online);
                    }

                    // After first heartbeat with device_settings, auto-start if needed.
                    // This handles the case where ConfigReceivedFromServer was false at boot,
                    // but now we have confirmed config from server.
                    if (_agentService.CurrentState == AgentState.Online)
                    {
                        var serverCfg = _authManager.ServerConfig;
                        if (serverCfg is { ConfigReceivedFromServer: true, AutoStart: true, RecordingEnabled: true })
                        {
                            _logger.LogInformation("Server confirmed auto_start=true via heartbeat, starting recording");
                            await _commandHandler.AutoStartRecordingAsync(baseUrl, ct);
                            if (_captureManager.IsRecording)
                            {
                                _agentService.SetState(AgentState.Recording);
                            }
                        }
                    }
                }

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

                // Update status provider on error
                if (_statusProvider is AgentStatusProvider asp)
                {
                    asp.SetConnectionStatus("error");
                    asp.SetLastError(ex.Message);
                }
            }

            await Task.Delay(TimeSpan.FromSeconds(interval), ct);
        }
    }

    private void ApplyDeviceSettings(Dictionary<string, JsonElement> ds)
    {
        try
        {
            var cfg = _authManager.ServerConfig ?? new ServerConfig();
            var changed = false;

            if (ds.TryGetValue("capture_fps", out var fpsEl) && fpsEl.TryGetInt32(out var fps) && fps > 0)
            { cfg.CaptureFps = fps; changed = true; }
            if (ds.TryGetValue("resolution", out var resEl) && resEl.GetString() is string res && res.Length > 0)
            { cfg.Resolution = res; changed = true; }
            if (ds.TryGetValue("quality", out var qEl) && qEl.GetString() is string q && q.Length > 0)
            { cfg.Quality = q; changed = true; }
            if (ds.TryGetValue("segment_duration_sec", out var segEl) && segEl.TryGetInt32(out var seg) && seg > 0)
            { cfg.SegmentDurationSec = seg; changed = true; }
            if (ds.TryGetValue("auto_start", out var autoEl))
            { cfg.AutoStart = autoEl.GetBoolean(); changed = true; }
            if (ds.TryGetValue("session_max_duration_hours", out var maxHEl) && maxHEl.TryGetInt32(out var maxH) && maxH > 0)
            { cfg.SessionMaxDurationHours = maxH; changed = true; }
            if (ds.TryGetValue("session_max_duration_min", out var maxMEl) && maxMEl.TryGetInt32(out var maxM) && maxM > 0)
            { cfg.SessionMaxDurationMin = maxM; changed = true; }
            if (ds.TryGetValue("heartbeat_interval_sec", out var hbEl) && hbEl.TryGetInt32(out var hb) && hb > 0)
            { cfg.HeartbeatIntervalSec = hb; changed = true; }

            if (ds.TryGetValue("recording_enabled", out var recEl) &&
                (recEl.ValueKind == JsonValueKind.True || recEl.ValueKind == JsonValueKind.False))
            {
                var newValue = recEl.GetBoolean();
                if (cfg.RecordingEnabled != newValue)
                {
                    _logger.LogInformation("recording_enabled changed: {Old} -> {New}", cfg.RecordingEnabled, newValue);
                    cfg.RecordingEnabled = newValue;
                    changed = true;
                }
            }

            if (changed)
            {
                // Mark that we received config from the actual server
                cfg.ConfigReceivedFromServer = true;
                _authManager.UpdateServerConfig(cfg);
                _logger.LogInformation(
                    "Device settings updated from heartbeat: fps={Fps}, res={Res}, quality={Quality}, " +
                    "autoStart={AutoStart}, sessionMaxMin={SessionMaxMin}",
                    cfg.CaptureFps, cfg.Resolution, cfg.Quality,
                    cfg.AutoStart, cfg.SessionMaxDurationMin);
            }
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Failed to apply device settings from heartbeat");
        }
    }
}

public class HeartbeatResponse
{
    public string? ServerTs { get; set; }
    public List<PendingCommand>? PendingCommands { get; set; }
    public int NextHeartbeatSec { get; set; } = 30;
    public Dictionary<string, JsonElement>? DeviceSettings { get; set; }
}

public class PendingCommand
{
    public string? Id { get; set; }
    public string? CommandType { get; set; }
    public Dictionary<string, object>? Payload { get; set; }
    public string? CreatedTs { get; set; }
}
