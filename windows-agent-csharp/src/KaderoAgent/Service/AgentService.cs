using KaderoAgent.Audit;
using KaderoAgent.Auth;
using KaderoAgent.Capture;
using KaderoAgent.Command;
using KaderoAgent.Storage;
using KaderoAgent.Upload;
using KaderoAgent.Util;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace KaderoAgent.Service;

public class AgentService : BackgroundService
{
    private readonly AuthManager _authManager;
    private readonly LocalDatabase _db;
    private readonly UploadQueue _uploadQueue;
    private readonly SegmentFileManager _fileManager;
    private readonly CredentialStore _credentialStore;
    private readonly CommandHandler _commandHandler;
    private readonly ScreenCaptureManager _captureManager;
    private readonly ApiClient _apiClient;
    private readonly SessionWatcher? _sessionWatcher;
    private readonly ILogger<AgentService> _logger;

    private volatile AgentState _currentState = AgentState.Starting;
    private readonly object _stateLock = new();

    /// <summary>Current agent lifecycle state. Thread-safe read via volatile.</summary>
    public AgentState CurrentState => _currentState;

    public AgentService(AuthManager authManager, LocalDatabase db, UploadQueue uploadQueue,
        SegmentFileManager fileManager, CredentialStore credentialStore,
        CommandHandler commandHandler, ScreenCaptureManager captureManager,
        ApiClient apiClient, ILogger<AgentService> logger,
        SessionWatcher? sessionWatcher = null)
    {
        _authManager = authManager;
        _db = db;
        _uploadQueue = uploadQueue;
        _fileManager = fileManager;
        _credentialStore = credentialStore;
        _commandHandler = commandHandler;
        _captureManager = captureManager;
        _apiClient = apiClient;
        _sessionWatcher = sessionWatcher;
        _logger = logger;
    }

    /// <summary>
    /// Transition to a new agent state. Logs the transition with structured fields.
    /// Thread-safe via lock on _stateLock.
    /// </summary>
    public void SetState(AgentState newState)
    {
        lock (_stateLock)
        {
            var oldState = _currentState;
            if (oldState == newState) return;

            _currentState = newState;
            _logger.LogInformation("Agent state transition: {OldState} -> {NewState}",
                oldState.ToHeartbeatStatus(), newState.ToHeartbeatStatus());
        }
    }

    protected override async Task ExecuteAsync(CancellationToken ct)
    {
        _logger.LogInformation("KaderoAgent service starting...");
        SetState(AgentState.Starting);

        // Transition to Configuring before auth retry loop
        SetState(AgentState.Configuring);

        // Initialize auth with retry -- network may not be ready after system reboot.
        // Exponential backoff: 5, 10, 20, 40, 80, 120, 120... (max 10 attempts, ~10 min total)
        var authAttempt = 0;
        const int maxAuthAttempts = 10;
        while (!ct.IsCancellationRequested)
        {
            var ok = await _authManager.InitializeAsync();
            if (ok) break;

            authAttempt++;
            if (authAttempt >= maxAuthAttempts)
            {
                _logger.LogError("Auth failed after {Attempts} attempts. No valid credentials -- please run setup or check network.", authAttempt);
                SetState(AgentState.Error);
                return;
            }

            var delaySec = Math.Min(5 * (1 << Math.Min(authAttempt - 1, 4)), 120);
            _logger.LogWarning("Auth attempt {Attempt}/{Max} failed, retrying in {Delay}s...",
                authAttempt, maxAuthAttempts, delaySec);
            try { await Task.Delay(TimeSpan.FromSeconds(delaySec), ct); }
            catch (OperationCanceledException) { return; }
        }
        if (ct.IsCancellationRequested) return;

        _logger.LogInformation("Authenticated as device {DeviceId}", _authManager.DeviceId);

        // After auth OK: determine initial state based on user session
        if (_sessionWatcher != null && !_sessionWatcher.IsSessionActive)
        {
            SetState(AgentState.AwaitingUser);
            _logger.LogInformation("Session is locked/no user, state set to AwaitingUser");
        }
        else
        {
            SetState(AgentState.Online);
        }

        var creds = _credentialStore.Load();
        var baseUrl = creds?.ServerUrl?.TrimEnd('/') ?? "";

        // Auto-start recording FIRST (before pending segments, which can block).
        // Only auto-start if the server has actually confirmed the config (ConfigReceivedFromServer).
        // On fresh install with default config, autostart is always false.
        try
        {
            if (_currentState == AgentState.AwaitingUser)
            {
                _logger.LogInformation("Session is locked, skipping auto-start");
            }
            else
            {
                var serverCfg = _authManager.ServerConfig;
                var configFromServer = serverCfg?.ConfigReceivedFromServer ?? false;

                if (configFromServer && !(serverCfg?.RecordingEnabled ?? true))
                {
                    _logger.LogInformation("Recording disabled by token policy, skipping auto-start on boot");
                }
                else if (!configFromServer)
                {
                    _logger.LogInformation("ServerConfig not yet confirmed by server, skipping auto-start. " +
                        "Will start recording when heartbeat delivers device_settings with auto_start=true.");
                }
                else
                {
                    await _commandHandler.AutoStartRecordingAsync(baseUrl, ct);
                    if (_captureManager.IsRecording)
                    {
                        SetState(AgentState.Recording);
                    }
                }
            }
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Auto-start recording failed, will retry on next cycle");
            SetState(AgentState.Error);
        }

        // Upload any pending segments from offline buffer (in background)
        var pending = _db.GetPendingSegments();
        if (pending.Count > 0)
        {
            _logger.LogInformation("Found {Count} pending segments, uploading...", pending.Count);
            _uploadQueue.StartProcessing(baseUrl, ct);
            _ = Task.Run(async () =>
            {
                foreach (var seg in pending)
                {
                    if (ct.IsCancellationRequested) break;
                    if (File.Exists(seg.FilePath))
                        await _uploadQueue.EnqueueAsync(seg);
                    else
                        _db.MarkSegmentUploaded(seg.FilePath);
                }
                _logger.LogInformation("Pending segments enqueue complete");
            }, ct);
        }

        // Main loop - periodic maintenance + monitoring
        try
        {
            while (!ct.IsCancellationRequested)
            {
                try
                {
                    // Evict old segments when buffer exceeds limit
                    _fileManager.EvictOldSegments();

                    // Check if FFmpeg crashed and restart
                    await _commandHandler.CheckAndRecoverAsync(baseUrl, ct);

                    // Sync state with actual recording status
                    SyncStateWithRecording();

                    // Check session max duration and rotate
                    await _commandHandler.CheckSessionRotationAsync(baseUrl, ct);
                }
                catch (Exception ex)
                {
                    _logger.LogError(ex, "Maintenance cycle error");
                }

                await Task.Delay(TimeSpan.FromSeconds(30), ct);
            }
        }
        catch (OperationCanceledException)
        {
            // Expected on shutdown
        }

        // Shutdown: send final heartbeat with status="stopped"
        await SendFinalHeartbeatAsync(baseUrl);
    }

    /// <summary>
    /// Sync the agent state with the actual recording/session status.
    /// Handles cases where state diverged (e.g. FFmpeg crash recovery changed recording status).
    /// </summary>
    private void SyncStateWithRecording()
    {
        // Don't overwrite terminal/special states
        if (_currentState is AgentState.Stopped or AgentState.Configuring or AgentState.Starting)
            return;

        // If session is not active (locked/logoff), state should be Idle or AwaitingUser
        if (_sessionWatcher != null && !_sessionWatcher.IsSessionActive)
        {
            if (_currentState != AgentState.Idle && _currentState != AgentState.AwaitingUser)
                SetState(AgentState.Idle);
            return;
        }

        // Session is active: sync based on recording
        if (_captureManager.IsRecording)
        {
            if (_currentState != AgentState.Recording)
                SetState(AgentState.Recording);
        }
        else if (_captureManager.HasCrashed)
        {
            if (_currentState != AgentState.Error)
                SetState(AgentState.Error);
        }
        else
        {
            // Not recording, no crash -- online
            if (_currentState == AgentState.Recording || _currentState == AgentState.Error)
                SetState(AgentState.Online);
        }
    }

    /// <summary>
    /// Best-effort final heartbeat on shutdown. Timeout 3 seconds, never blocks shutdown.
    /// </summary>
    private async Task SendFinalHeartbeatAsync(string baseUrl)
    {
        SetState(AgentState.Stopped);

        if (string.IsNullOrEmpty(_authManager.DeviceId) || string.IsNullOrEmpty(baseUrl))
            return;

        try
        {
            var url = $"{baseUrl}/api/cp/v1/devices/{_authManager.DeviceId}/heartbeat";
            var body = new
            {
                status = AgentState.Stopped.ToHeartbeatStatus(),
                session_locked = _sessionWatcher != null && !_sessionWatcher.IsSessionActive,
                agent_version = "1.0.0",
                metrics = new
                {
                    cpu_percent = 0.0,
                    memory_mb = 0.0,
                    disk_free_gb = 0.0,
                    segments_queued = 0
                }
            };

            using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(3));
            await _apiClient.PutAsync<object>(url, body, cts.Token);
            _logger.LogInformation("Final heartbeat sent: status=stopped");
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Failed to send final heartbeat (best-effort)");
        }
    }
}
