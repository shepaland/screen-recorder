using System.Text.Json;
using KaderoAgent.Auth;
using KaderoAgent.Capture;
using KaderoAgent.Configuration;
using KaderoAgent.Service;
using KaderoAgent.Storage;
using KaderoAgent.Upload;
using KaderoAgent.Util;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;

namespace KaderoAgent.Command;

public class CommandHandler
{
    private readonly ScreenCaptureManager _captureManager;
    private readonly SessionManager _sessionManager;
    private readonly LocalDatabase _db;
    private readonly AuthManager _authManager;
    private readonly ApiClient _apiClient;
    private readonly IOptions<AgentConfig> _config;
    private readonly ILogger<CommandHandler> _logger;

    // AgentService is set after construction to avoid circular DI
    private AgentService? _agentService;

    private DateTime? _sessionStartTime;
    private string _currentBaseUrl = "";
    private bool _isPausedByLock;
    private readonly SemaphoreSlim _startLock = new(1, 1);

    // Crash recovery backoff
    private int _consecutiveFailures;
    private const int MaxConsecutiveFailures = 5;
    private DateTime _lastRecoveryAttempt = DateTime.MinValue;

    public CommandHandler(ScreenCaptureManager captureManager, SessionManager sessionManager,
        LocalDatabase db, AuthManager authManager, ApiClient apiClient,
        IOptions<AgentConfig> config, ILogger<CommandHandler> logger)
    {
        _captureManager = captureManager;
        _sessionManager = sessionManager;
        _db = db;
        _authManager = authManager;
        _apiClient = apiClient;
        _config = config;
        _logger = logger;
    }

    /// <summary>Set AgentService reference for state transitions. Called after DI resolution to avoid circular dependency.</summary>
    public void SetAgentService(AgentService agentService) => _agentService = agentService;

    public string? CurrentSessionId => _sessionManager.CurrentSessionId;
    public bool IsPausedByLock => _isPausedByLock;

    // Segment context for video timecode binding
    private int _lastSegmentSequence = -1;
    private DateTime _lastSegmentStartTs = DateTime.MinValue;
    public int LastSegmentSequence => _lastSegmentSequence;
    public DateTime LastSegmentStartTs => _lastSegmentStartTs;

    public async Task PauseRecordingAsync(CancellationToken ct)
    {
        if (!_captureManager.IsRecording && !_isPausedByLock) return;

        _logger.LogInformation("Pausing recording: screen locked");
        _captureManager.Stop();

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

        if (!(_authManager.ServerConfig?.RecordingEnabled ?? true))
        {
            _logger.LogInformation("Recording disabled by token policy, skipping resume after unlock");
            return;
        }

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

        if (!(_authManager.ServerConfig?.RecordingEnabled ?? true))
        {
            _logger.LogInformation("Recording disabled by token policy, skipping logon auto-start");
            return;
        }

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
                    if (!(_authManager.ServerConfig?.RecordingEnabled ?? true))
                    {
                        _logger.LogWarning("Ignoring START_RECORDING command: recording disabled by token policy");
                        break;
                    }
                    _consecutiveFailures = 0; // Reset on explicit command
                    await StartRecording(baseUrl, ct);
                    if (_captureManager.IsRecording)
                        _agentService?.SetState(AgentState.Recording);
                    break;
                case "STOP_RECORDING":
                    await StopRecording(ct);
                    _agentService?.SetState(AgentState.Online);
                    break;
                case "UPDATE_SETTINGS":
                    UpdateSettings(cmd.Payload);
                    break;
                case "RESTART_AGENT":
                    Environment.Exit(0); // Service will auto-restart
                    break;
                case "UPLOAD_LOGS":
                    await UploadLogs(baseUrl, ct);
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

        if (!(_authManager.ServerConfig?.RecordingEnabled ?? true))
        {
            _logger.LogInformation("Recording disabled by token policy, skipping auto-start");
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

            // Cleanup session after crash so HeartbeatService can re-attempt auto-start
            try { await _sessionManager.EndSessionAsync(ct); } catch { }

            if (_consecutiveFailures >= MaxConsecutiveFailures)
            {
                _logger.LogError("Max consecutive FFmpeg failures ({Max}) reached, stopping recovery. " +
                    "HeartbeatService will continue retrying with backoff.",
                    MaxConsecutiveFailures);
                return;
            }

            // Exponential backoff: 30s, 60s, 120s, 240s between recovery attempts
            var backoffSeconds = 30 * (1 << Math.Min(_consecutiveFailures - 1, 3));
            var elapsed = DateTime.UtcNow - _lastRecoveryAttempt;
            if (elapsed.TotalSeconds < backoffSeconds)
            {
                _logger.LogInformation("Crash recovery backoff: waiting {Remaining}s before retry",
                    (int)(backoffSeconds - elapsed.TotalSeconds));
                return;
            }

            _lastRecoveryAttempt = DateTime.UtcNow;
            await StartRecording(baseUrl, ct);
        }
    }

    /// <summary>Check if session exceeded max duration and rotate.
    /// Uses SessionMaxDurationMin (priority) or SessionMaxDurationHours * 60 (fallback).</summary>
    public async Task CheckSessionRotationAsync(string baseUrl, CancellationToken ct)
    {
        if (!_captureManager.IsRecording || _sessionStartTime == null) return;

        var serverCfg = _authManager.ServerConfig;
        var maxMinutes = serverCfg?.GetEffectiveSessionMaxDurationMin(_config.Value.SessionMaxDurationMin)
                         ?? _config.Value.SessionMaxDurationMin;
        var elapsed = DateTime.UtcNow - _sessionStartTime.Value;

        if (elapsed.TotalMinutes >= maxMinutes)
        {
            _logger.LogInformation("Session max duration ({MaxMin}min) reached, rotating...", maxMinutes);
            await RotateSessionAsync(baseUrl, ct);
        }
    }

    private async Task StartRecording(string baseUrl, CancellationToken ct)
    {
        if (_captureManager.IsRecording) return;

        // Prevent concurrent StartRecording calls (race between AgentService and HeartbeatService)
        if (!await _startLock.WaitAsync(0, ct))
        {
            _logger.LogDebug("StartRecording already in progress, skipping");
            return;
        }
        try
        {
        if (_captureManager.IsRecording) return; // double-check under lock

        if (!(_authManager.ServerConfig?.RecordingEnabled ?? true))
        {
            _logger.LogInformation("Recording disabled by token policy, ignoring StartRecording");
            return;
        }

        _currentBaseUrl = baseUrl;

        // Get settings: server overrides > local defaults
        var serverCfg = _authManager.ServerConfig;
        var fps = serverCfg?.CaptureFps > 0 ? serverCfg.CaptureFps : _config.Value.CaptureFps;
        fps = Math.Max(fps, 1); // Safety floor: never allow FPS < 1
        var segDuration = serverCfg?.SegmentDurationSec > 0 ? serverCfg.SegmentDurationSec : _config.Value.SegmentDurationSec;
        var quality = !string.IsNullOrEmpty(serverCfg?.Quality) ? serverCfg.Quality : _config.Value.Quality;
        var resolution = !string.IsNullOrEmpty(serverCfg?.Resolution) ? serverCfg.Resolution : _config.Value.Resolution;

        // Start FFmpeg FIRST — only create server session if capture actually works.
        // This prevents orphaned empty sessions when no user desktop is available.
        var tempSessionId = Guid.NewGuid().ToString();
        var started = _captureManager.Start(tempSessionId, fps, segDuration, quality, resolution);
        if (!started)
        {
            _logger.LogError("FFmpeg failed to start, aborting recording (no server session created)");
            _consecutiveFailures++;
            return;
        }

        // FFmpeg is running — now create session on server
        string sessionId;
        try
        {
            sessionId = await _sessionManager.StartSessionAsync(fps, resolution, ct);
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Failed to create server session, using local session ID");
            sessionId = tempSessionId;
        }

        // FFmpeg writes to tempSessionId dir, but uploads use server sessionId.
        // WatchSegments reads from _captureManager.OutputDirectory (tempSessionId dir)
        // and uploads with the server sessionId — this is fine, segment files are the same.

        // Don't reset _consecutiveFailures here — only reset after first segment is produced
        // (in WatchSegments) to prevent infinite crash loops when gdigrab has no desktop
        _sessionStartTime = DateTime.UtcNow;

        // Start watching for new segments
        _ = Task.Run(() => WatchSegments(sessionId, baseUrl, ct), ct);

        var maxMin = serverCfg?.GetEffectiveSessionMaxDurationMin(_config.Value.SessionMaxDurationMin)
                     ?? _config.Value.SessionMaxDurationMin;
        _logger.LogInformation("Recording started: session={Session}, fps={Fps}, res={Res}, maxMin={MaxMin}",
            sessionId, fps, resolution, maxMin);
        }
        finally
        {
            _startLock.Release();
        }
    }

    /// <summary>
    /// Restart recording with updated settings (e.g., FPS changed via heartbeat device_settings).
    /// Stops current FFmpeg, ends session, starts new recording with latest ServerConfig.
    /// </summary>
    public async Task RestartRecordingAsync(string baseUrl, CancellationToken ct)
    {
        if (!_captureManager.IsRecording)
        {
            _logger.LogDebug("RestartRecording: not recording, skipping");
            return;
        }

        _logger.LogInformation("Restarting recording due to settings change");
        _captureManager.Stop();
        try { await _sessionManager.EndSessionAsync(ct); } catch { }
        _sessionStartTime = null;

        await Task.Delay(1000, ct);
        await StartRecording(baseUrl, ct);
    }

    private async Task StopRecording(CancellationToken ct)
    {
        _captureManager.Stop();
        _sessionStartTime = null;

        await _sessionManager.EndSessionAsync(ct);
    }

    /// <summary>Stop recording externally (called from HeartbeatService when recording_enabled=false).</summary>
    public async Task StopRecordingExternalAsync(CancellationToken ct)
    {
        await StopRecording(ct);
    }

    /// <summary>Read log files (tail, max 2MB each) and upload them to the server.</summary>
    private async Task UploadLogs(string baseUrl, CancellationToken ct)
    {
        _logger.LogInformation("UPLOAD_LOGS: starting log collection");

        var logDir = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData),
            "Kadero", "logs");

        if (!Directory.Exists(logDir))
        {
            _logger.LogWarning("UPLOAD_LOGS: log directory not found: {Dir}", logDir);
            return;
        }

        var logFiles = new[] { "kadero-agent.log", "kadero-http.log", "kadero-pipe.log" };
        const int maxBytes = 500_000; // 500KB per file (total ~1.5MB to avoid nginx limits)
        var entries = new List<object>();

        foreach (var logFile in logFiles)
        {
            var filePath = Path.Combine(logDir, logFile);
            if (!File.Exists(filePath))
            {
                _logger.LogDebug("UPLOAD_LOGS: file not found: {File}", filePath);
                continue;
            }

            try
            {
                // Read file with shared access (log4net uses MinimalLock)
                string content;
                using (var stream = new FileStream(filePath, FileMode.Open, FileAccess.Read, FileShare.ReadWrite))
                using (var reader = new StreamReader(stream))
                {
                    content = await reader.ReadToEndAsync(ct);
                }

                if (string.IsNullOrEmpty(content)) continue;

                // Take last 2MB (tail)
                if (content.Length > maxBytes)
                    content = content[^maxBytes..];

                var logType = Path.GetFileNameWithoutExtension(logFile);
                entries.Add(new
                {
                    log_type = logType,
                    content,
                    from_ts = DateTime.UtcNow.AddHours(-24).ToString("O"),
                    to_ts = DateTime.UtcNow.ToString("O")
                });

                _logger.LogInformation("UPLOAD_LOGS: prepared {Type}: {Bytes} bytes", logType, content.Length);
            }
            catch (Exception ex)
            {
                _logger.LogWarning(ex, "UPLOAD_LOGS: failed to read {File}", logFile);
            }
        }

        if (entries.Count == 0)
        {
            _logger.LogInformation("UPLOAD_LOGS: no log data to upload");
            return;
        }

        // Upload to server via control-plane API
        var deviceId = _authManager.DeviceId;
        var url = $"{baseUrl}/api/cp/v1/devices/{deviceId}/logs";
        _logger.LogInformation("UPLOAD_LOGS: posting {Count} logs to {Url}", entries.Count, url);
        await _apiClient.PostAsync<object>(url, entries, ct);
        _logger.LogInformation("UPLOAD_LOGS: uploaded {Count} log files to server", entries.Count);
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

        var serverCfg = _authManager.ServerConfig ?? new ServerConfig();
        var changed = false;

        // JSON deserialization produces JsonElement, not native types — must handle both
        if (payload.TryGetValue("capture_fps", out var fpsObj))
        {
            if (fpsObj is JsonElement fpsEl && fpsEl.TryGetInt32(out var fps) && fps > 0)
            { serverCfg.CaptureFps = fps; changed = true; }
            else if (fpsObj is int fpsInt && fpsInt > 0)
            { serverCfg.CaptureFps = fpsInt; changed = true; }
        }
        if (payload.TryGetValue("resolution", out var resObj))
        {
            var res = resObj is JsonElement resEl ? resEl.GetString() : resObj as string;
            if (!string.IsNullOrEmpty(res))
            { serverCfg.Resolution = res; changed = true; }
        }
        if (payload.TryGetValue("quality", out var qObj))
        {
            var q = qObj is JsonElement qEl ? qEl.GetString() : qObj as string;
            if (!string.IsNullOrEmpty(q))
            { serverCfg.Quality = q; changed = true; }
        }
        if (payload.TryGetValue("segment_duration_sec", out var segObj))
        {
            if (segObj is JsonElement segEl && segEl.TryGetInt32(out var seg) && seg > 0)
            { serverCfg.SegmentDurationSec = seg; changed = true; }
            else if (segObj is int segInt && segInt > 0)
            { serverCfg.SegmentDurationSec = segInt; changed = true; }
        }
        if (payload.TryGetValue("auto_start", out var autoObj))
        {
            if (autoObj is JsonElement autoEl)
            { serverCfg.AutoStart = autoEl.GetBoolean(); changed = true; }
            else if (autoObj is bool autoBool)
            { serverCfg.AutoStart = autoBool; changed = true; }
        }
        if (payload.TryGetValue("session_max_duration_min", out var maxMinObj))
        {
            if (maxMinObj is JsonElement maxMinEl && maxMinEl.TryGetInt32(out var maxMin) && maxMin > 0)
            { serverCfg.SessionMaxDurationMin = maxMin; changed = true; }
            else if (maxMinObj is int maxMinInt && maxMinInt > 0)
            { serverCfg.SessionMaxDurationMin = maxMinInt; changed = true; }
        }

        if (changed)
        {
            serverCfg.ConfigReceivedFromServer = true;
            _authManager.UpdateServerConfig(serverCfg);
        }

        _logger.LogInformation("Settings updated from command: fps={Fps}, res={Res}, quality={Quality}, sessionMaxMin={MaxMin}",
            serverCfg.CaptureFps, serverCfg.Resolution, serverCfg.Quality,
            serverCfg.GetEffectiveSessionMaxDurationMin(_config.Value.SessionMaxDurationMin));
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

                // Track segment context for video timecode binding
                _lastSegmentSequence = sequenceNum;
                _lastSegmentStartTs = fileInfo.CreationTimeUtc;

                // Persist to SQLite — DataSyncService will upload
                try
                {
                    _db.InsertSegment(
                        filePath: file,
                        sessionId: sessionId,
                        sequenceNum: sequenceNum,
                        sizeBytes: fileInfo.Length,
                        recordedAt: fileInfo.CreationTimeUtc.ToString("o"));
                }
                catch (Exception ex)
                {
                    _logger.LogWarning(ex, "Failed to persist segment to SQLite");
                }
                sequenceNum++;
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
                    var remFileInfo = new FileInfo(file);
                    try
                    {
                        _db.InsertSegment(
                            filePath: file,
                            sessionId: sessionId,
                            sequenceNum: sequenceNum,
                            sizeBytes: remFileInfo.Length,
                            recordedAt: remFileInfo.CreationTimeUtc.ToString("o"));
                    }
                    catch (Exception ex)
                    {
                        _logger.LogWarning(ex, "Failed to persist remaining segment to SQLite");
                    }
                    sequenceNum++;
                }
            }
        }
        catch (OperationCanceledException) { }
    }
}
