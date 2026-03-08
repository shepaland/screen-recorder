using KaderoAgent.Auth;
using KaderoAgent.Capture;
using KaderoAgent.Configuration;
using KaderoAgent.Upload;
using KaderoAgent.Util;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;

namespace KaderoAgent.Command;

public class CommandHandler
{
    private readonly ScreenCaptureManager _captureManager;
    private readonly SessionManager _sessionManager;
    private readonly UploadQueue _uploadQueue;
    private readonly AuthManager _authManager;
    private readonly ApiClient _apiClient;
    private readonly IOptions<AgentConfig> _config;
    private readonly ILogger<CommandHandler> _logger;

    private DateTime? _sessionStartTime;
    private string _currentBaseUrl = "";
    private bool _isPausedByLock;

    // Crash recovery backoff
    private int _consecutiveFailures;
    private const int MaxConsecutiveFailures = 5;
    private DateTime _lastRecoveryAttempt = DateTime.MinValue;

    public CommandHandler(ScreenCaptureManager captureManager, SessionManager sessionManager,
        UploadQueue uploadQueue, AuthManager authManager, ApiClient apiClient,
        IOptions<AgentConfig> config, ILogger<CommandHandler> logger)
    {
        _captureManager = captureManager;
        _sessionManager = sessionManager;
        _uploadQueue = uploadQueue;
        _authManager = authManager;
        _apiClient = apiClient;
        _config = config;
        _logger = logger;
    }

    public string? CurrentSessionId => _sessionManager.CurrentSessionId;
    public bool IsPausedByLock => _isPausedByLock;

    public async Task PauseRecordingAsync(CancellationToken ct)
    {
        if (!_captureManager.IsRecording && !_isPausedByLock) return;

        _logger.LogInformation("Pausing recording: screen locked");
        _captureManager.Stop();
        _uploadQueue.StopProcessing();

        try { await _sessionManager.EndSessionAsync(ct); } catch (Exception ex)
        {
            _logger.LogWarning(ex, "Failed to end session during pause");
        }

        _sessionStartTime = null;
        _isPausedByLock = true;
    }

    public async Task ResumeRecordingAsync(string baseUrl, CancellationToken ct)
    {
        if (!_isPausedByLock) return;

        _isPausedByLock = false;
        _logger.LogInformation("Resuming recording: screen unlocked");

        var serverCfg = _authManager.ServerConfig;
        var autoStart = serverCfg?.AutoStart ?? _config.Value.AutoStart;

        if (!autoStart)
        {
            _logger.LogInformation("AutoStart is disabled, not resuming after unlock");
            return;
        }

        await StartRecording(baseUrl, ct);
    }

    /// <summary>
    /// Handle user logon (new user session after logoff/switch).
    /// Resets pause state, clears stale FFmpeg state, and auto-starts recording.
    /// Unlike ResumeRecordingAsync, this always attempts to start (not gated by _isPausedByLock).
    /// </summary>
    public async Task HandleSessionLogonAsync(string baseUrl, CancellationToken ct)
    {
        _logger.LogInformation("Handling session logon: resetting state and auto-starting");

        // Reset pause flag (Logoff sets _isPausedByLock = true via PauseRecordingAsync)
        _isPausedByLock = false;

        // Stop any stale recording state (FFmpeg from previous user session is dead)
        if (_captureManager.IsRecording)
        {
            _logger.LogInformation("Clearing stale recording state from previous user session");
            _captureManager.ResetAfterCrash();
        }

        // Reset consecutive failures for fresh start
        _consecutiveFailures = 0;

        // Small delay to let the new Windows session stabilize (desktop, DWM, explorer)
        await Task.Delay(3000, ct);

        // Auto-start recording for the new user
        await AutoStartRecordingAsync(baseUrl, ct);
    }

    public async Task HandleAsync(PendingCommand cmd, string baseUrl, CancellationToken ct)
    {
        _logger.LogInformation("Handling command: {Type} ({Id})", cmd.CommandType, cmd.Id);

        try
        {
            switch (cmd.CommandType?.ToUpper())
            {
                case "START_RECORDING":
                    _consecutiveFailures = 0; // Reset on explicit command
                    await StartRecording(baseUrl, ct);
                    break;
                case "STOP_RECORDING":
                    await StopRecording(ct);
                    break;
                case "UPDATE_SETTINGS":
                    UpdateSettings(cmd.Payload);
                    break;
                case "RESTART_AGENT":
                    Environment.Exit(0); // Service will auto-restart
                    break;
            }

            // Acknowledge
            var ackUrl = $"{baseUrl}/api/cp/v1/devices/commands/{cmd.Id}/ack";
            await _apiClient.PutAsync<object>(ackUrl, new { status = "acknowledged" }, ct);
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Command {Id} failed", cmd.Id);
            var ackUrl = $"{baseUrl}/api/cp/v1/devices/commands/{cmd.Id}/ack";
            await _apiClient.PutAsync<object>(ackUrl, new { status = "failed", result = new { message = ex.Message } }, ct);
        }
    }

    /// <summary>Auto-start recording using server config or defaults. Called from AgentService on boot.</summary>
    public async Task AutoStartRecordingAsync(string baseUrl, CancellationToken ct)
    {
        var serverCfg = _authManager.ServerConfig;
        var autoStart = serverCfg?.AutoStart ?? _config.Value.AutoStart;

        if (!autoStart)
        {
            _logger.LogInformation("AutoStart is disabled, waiting for server command");
            return;
        }

        if (_captureManager.IsRecording)
        {
            _logger.LogDebug("Already recording, skip auto-start");
            return;
        }

        _logger.LogInformation("Auto-starting recording...");
        await StartRecording(baseUrl, ct);
    }

    /// <summary>Check if FFmpeg crashed and restart recording with exponential backoff.</summary>
    public async Task CheckAndRecoverAsync(string baseUrl, CancellationToken ct)
    {
        if (!_captureManager.IsRecording) return;

        if (_captureManager.HasCrashed)
        {
            _consecutiveFailures++;
            _logger.LogWarning("FFmpeg crash detected (attempt {Attempt}/{Max})",
                _consecutiveFailures, MaxConsecutiveFailures);

            _captureManager.ResetAfterCrash();

            if (_consecutiveFailures >= MaxConsecutiveFailures)
            {
                _logger.LogError("Max consecutive FFmpeg failures ({Max}) reached, stopping recovery. " +
                    "Will retry on next server START_RECORDING command or agent restart.",
                    MaxConsecutiveFailures);
                try { await _sessionManager.EndSessionAsync(ct); } catch { }
                return;
            }

            // Exponential backoff: 30s, 60s, 120s, 240s between recovery attempts
            var backoffSeconds = 30 * (1 << Math.Min(_consecutiveFailures - 1, 3));
            var elapsed = DateTime.UtcNow - _lastRecoveryAttempt;
            if (elapsed.TotalSeconds < backoffSeconds)
            {
                _logger.LogInformation("Crash recovery backoff: waiting {Remaining}s before retry",
                    (int)(backoffSeconds - elapsed.TotalSeconds));
                try { await _sessionManager.EndSessionAsync(ct); } catch { }
                return;
            }

            _lastRecoveryAttempt = DateTime.UtcNow;

            // End old session, start new one
            try { await _sessionManager.EndSessionAsync(ct); } catch { }
            await StartRecording(baseUrl, ct);
        }
    }

    /// <summary>Check if session exceeded max duration and rotate.</summary>
    public async Task CheckSessionRotationAsync(string baseUrl, CancellationToken ct)
    {
        if (!_captureManager.IsRecording || _sessionStartTime == null) return;

        var serverCfg = _authManager.ServerConfig;
        var maxHours = serverCfg?.SessionMaxDurationHours ?? _config.Value.SessionMaxDurationHours;
        var elapsed = DateTime.UtcNow - _sessionStartTime.Value;

        if (elapsed.TotalHours >= maxHours)
        {
            _logger.LogInformation("Session max duration ({MaxH}h) reached, rotating...", maxHours);
            await RotateSessionAsync(baseUrl, ct);
        }
    }

    private async Task StartRecording(string baseUrl, CancellationToken ct)
    {
        if (_captureManager.IsRecording) return;

        _currentBaseUrl = baseUrl;

        // Get settings: server overrides > local defaults
        var serverCfg = _authManager.ServerConfig;
        var fps = serverCfg?.CaptureFps > 0 ? serverCfg.CaptureFps : _config.Value.CaptureFps;
        var segDuration = serverCfg?.SegmentDurationSec > 0 ? serverCfg.SegmentDurationSec : _config.Value.SegmentDurationSec;
        var quality = !string.IsNullOrEmpty(serverCfg?.Quality) ? serverCfg.Quality : _config.Value.Quality;
        var resolution = !string.IsNullOrEmpty(serverCfg?.Resolution) ? serverCfg.Resolution : _config.Value.Resolution;

        // Create session on server (handles 409 by closing stale session)
        string sessionId;
        try
        {
            sessionId = await _sessionManager.StartSessionAsync(fps, resolution, ct);
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Failed to create server session, using local session ID");
            sessionId = Guid.NewGuid().ToString();
        }

        var started = _captureManager.Start(sessionId, fps, segDuration, quality, resolution);
        if (!started)
        {
            _logger.LogError("FFmpeg failed to start, aborting recording");
            _consecutiveFailures++;
            // End the session we just created since FFmpeg didn't start
            try { await _sessionManager.EndSessionAsync(ct); } catch { }
            return;
        }

        // Don't reset _consecutiveFailures here — only reset after first segment is produced
        // (in WatchSegments) to prevent infinite crash loops when gdigrab has no desktop
        _uploadQueue.StartProcessing(baseUrl, ct);
        _sessionStartTime = DateTime.UtcNow;

        // Start watching for new segments
        _ = Task.Run(() => WatchSegments(sessionId, baseUrl, ct), ct);

        _logger.LogInformation("Recording started: session={Session}, fps={Fps}, res={Res}, maxH={MaxH}",
            sessionId, fps, resolution,
            serverCfg?.SessionMaxDurationHours ?? _config.Value.SessionMaxDurationHours);
    }

    private async Task StopRecording(CancellationToken ct)
    {
        _captureManager.Stop();
        _uploadQueue.StopProcessing();
        _sessionStartTime = null;

        await _sessionManager.EndSessionAsync(ct);
    }

    private async Task RotateSessionAsync(string baseUrl, CancellationToken ct)
    {
        _logger.LogInformation("Rotating recording session...");

        // Stop current
        _captureManager.Stop();
        try { await _sessionManager.EndSessionAsync(ct); } catch { }

        // Brief pause for FFmpeg cleanup
        await Task.Delay(1000, ct);

        // Start new session with same settings
        _sessionStartTime = null;
        await StartRecording(baseUrl, ct);
    }

    private void UpdateSettings(Dictionary<string, object>? payload)
    {
        if (payload == null) return;

        _logger.LogInformation("Updating settings from server command");

        // Settings will take effect on next recording start
        // (ServerConfig is the primary source, these are emergency overrides)
        if (payload.TryGetValue("capture_fps", out var fpsObj) && fpsObj is int fps)
            _config.Value.CaptureFps = fps;
        if (payload.TryGetValue("resolution", out var resObj) && resObj is string res)
            _config.Value.Resolution = res;
        if (payload.TryGetValue("quality", out var qObj) && qObj is string q)
            _config.Value.Quality = q;
        if (payload.TryGetValue("segment_duration_sec", out var segObj) && segObj is int seg)
            _config.Value.SegmentDurationSec = seg;
        if (payload.TryGetValue("auto_start", out var autoObj) && autoObj is bool auto)
            _config.Value.AutoStart = auto;

        _logger.LogInformation("Settings updated: fps={Fps}, res={Res}, quality={Quality}",
            _config.Value.CaptureFps, _config.Value.Resolution, _config.Value.Quality);
    }

    private async Task WatchSegments(string sessionId, string baseUrl, CancellationToken ct)
    {
        var dir = _captureManager.OutputDirectory;
        var sequenceNum = 0;
        var processedFiles = new HashSet<string>();

        while (!ct.IsCancellationRequested && _captureManager.IsRecording)
        {
            await Task.Delay(2000, ct); // Check every 2 seconds

            if (!Directory.Exists(dir)) continue;

            var files = Directory.GetFiles(dir, "segment_*.mp4")
                .OrderBy(f => f)
                .ToList();

            // Process all complete files (all except the last one being written)
            var completeFiles = files.Count > 1 ? files.Take(files.Count - 1) : Array.Empty<string>();

            foreach (var file in completeFiles)
            {
                if (processedFiles.Contains(file)) continue;

                // Skip 0-byte segments (gdigrab failure / no desktop)
                var fileInfo = new FileInfo(file);
                if (fileInfo.Length == 0)
                {
                    _logger.LogWarning("Skipping 0-byte segment {File} (possible gdigrab failure)", file);
                    processedFiles.Add(file);
                    try { File.Delete(file); } catch { }
                    continue;
                }

                processedFiles.Add(file);

                // First real segment produced — reset crash counter
                if (_consecutiveFailures > 0)
                {
                    _logger.LogInformation("First segment produced, resetting crash counter from {Count}", _consecutiveFailures);
                    _consecutiveFailures = 0;
                }

                await _uploadQueue.EnqueueAsync(new SegmentInfo
                {
                    FilePath = file,
                    SessionId = sessionId,
                    SequenceNum = sequenceNum++
                });
            }
        }

        // Upload remaining files after stop
        try
        {
            await Task.Delay(1000, ct);
            if (Directory.Exists(dir))
            {
                var remaining = Directory.GetFiles(dir, "segment_*.mp4")
                    .Where(f => !processedFiles.Contains(f))
                    .OrderBy(f => f);

                foreach (var file in remaining)
                {
                    await _uploadQueue.EnqueueAsync(new SegmentInfo
                    {
                        FilePath = file,
                        SessionId = sessionId,
                        SequenceNum = sequenceNum++
                    });
                }
            }
        }
        catch (OperationCanceledException) { }
    }
}
