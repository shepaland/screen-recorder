using KaderoAgent.Audit;
using KaderoAgent.Auth;
using KaderoAgent.Capture;
using KaderoAgent.Command;
using KaderoAgent.Storage;
using KaderoAgent.Util;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace KaderoAgent.Service;

public class AgentService : BackgroundService
{
    private readonly AuthManager _authManager;
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

    public AgentService(AuthManager authManager,
        SegmentFileManager fileManager, CredentialStore credentialStore,
        CommandHandler commandHandler, ScreenCaptureManager captureManager,
        ApiClient apiClient, ILogger<AgentService> logger,
        SessionWatcher? sessionWatcher = null)
    {
        _authManager = authManager;
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

        // If user session already active (Service started after user logon) —
        // launch headless analytics collector and start recording immediately
        if (_sessionWatcher == null || _sessionWatcher.IsSessionActive)
        {
            if (_sessionWatcher != null)
            {
                _logger.LogInformation("User session active, launching headless collector");
                LaunchHeadlessCollector();
            }

            // Immediate auto-start with local defaults (60s chunks, 720p, low, 1fps).
            // No server dependency — HeartbeatService will hot-restart with server params later.
            _logger.LogInformation("Starting recording immediately with local defaults");
            await _commandHandler.AutoStartRecordingAsync(baseUrl, ct);
            if (_captureManager.IsRecording)
                SetState(AgentState.Recording);
        }

        // Pending segments are handled by DataSyncService (reads from SQLite)

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

        // If session is not active (locked/logoff), state should be Idle or AwaitingUser.
        // Primary: check SessionWatcher._isSessionActive (set by WTS notifications).
        // Fallback: poll WTS API directly every 30s to catch missed WM_WTSSESSION_CHANGE.
        if (_sessionWatcher != null)
        {
            var eventDriven = !_sessionWatcher.IsSessionActive;
            var wtsPoll = !_sessionWatcher.CheckSessionActiveViaWts();

            if (eventDriven || wtsPoll)
            {
                if (_currentState != AgentState.Idle && _currentState != AgentState.AwaitingUser)
                {
                    _logger.LogInformation("Session inactive detected (event={EventDriven}, poll={WtsPoll}), transitioning to Idle",
                        eventDriven, wtsPoll);
                    SetState(AgentState.Idle);
                }
                return;
            }
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
        else if (_captureManager.DesktopUnavailableDetected)
        {
            if (_currentState != AgentState.DesktopUnavailable)
                SetState(AgentState.DesktopUnavailable);
        }
        else
        {
            // Not recording, no crash -- online (don't override DesktopUnavailable, HeartbeatService manages it)
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
                agent_version = GetType().Assembly.GetName().Version?.ToString() ?? "0.0.0",
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

    /// <summary>
    /// Launch headless analytics collector in user session via CreateProcessAsUser.
    /// Checks if already running, then launches if needed.
    /// </summary>
    private void LaunchHeadlessCollector()
    {
        try
        {
            var myPid = Environment.ProcessId;
            foreach (var p in System.Diagnostics.Process.GetProcessesByName("KaderoAgent"))
            {
                try
                {
                    if (p.Id != myPid && p.SessionId > 0)
                    {
                        _logger.LogDebug("Headless/tray already running: PID={Pid} Session={Session}", p.Id, p.SessionId);
                        return;
                    }
                }
                finally { p.Dispose(); }
            }

            var exePath = System.Diagnostics.Process.GetCurrentProcess().MainModule?.FileName
                          ?? Path.Combine(AppContext.BaseDirectory, "KaderoAgent.exe");
            var workDir = Path.GetDirectoryName(exePath);

            var pid = Capture.InteractiveProcessLauncher.LaunchInUserSession(
                exePath, "--headless", workDir, _logger);

            if (pid > 0)
                _logger.LogInformation("Headless collector launched from AgentService: PID={Pid}", pid);
            else
                _logger.LogWarning("Failed to launch headless collector from AgentService");
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "LaunchHeadlessCollector failed");
        }
    }
}
