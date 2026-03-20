using KaderoAgent.Capture;
using KaderoAgent.Command;
using KaderoAgent.Auth;
using KaderoAgent.Service;
using KaderoAgent.Storage;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace KaderoAgent.Audit;

public class SessionWatcher : BackgroundService
{
    private readonly IAuditEventSink _sink;
    private readonly CommandHandler _commandHandler;
    private readonly CredentialStore _credentialStore;
    private readonly AuthManager _authManager;
    private readonly ScreenCaptureManager _captureManager;
    private readonly UserSessionInfo _userSessionInfo;
    private readonly FocusIntervalSink _focusIntervalSink;
    private readonly InputEventSink _inputEventSink;
    private readonly WtsSessionNotifier _wtsNotifier;
    private readonly ILogger<SessionWatcher> _logger;

    // AgentService is resolved lazily to avoid circular DI (SessionWatcher is injected into AgentService).
    private readonly IServiceProvider _serviceProvider;
    private AgentService? _agentService;

    private volatile bool _isSessionActive = true;
    private readonly SemaphoreSlim _lock = new(1, 1);

    public bool IsSessionActive => _isSessionActive;

    public SessionWatcher(
        IAuditEventSink sink,
        CommandHandler commandHandler,
        CredentialStore credentialStore,
        AuthManager authManager,
        ScreenCaptureManager captureManager,
        UserSessionInfo userSessionInfo,
        FocusIntervalSink focusIntervalSink,
        InputEventSink inputEventSink,
        WtsSessionNotifier wtsNotifier,
        IServiceProvider serviceProvider,
        ILogger<SessionWatcher> logger)
    {
        _sink = sink;
        _commandHandler = commandHandler;
        _credentialStore = credentialStore;
        _authManager = authManager;
        _captureManager = captureManager;
        _userSessionInfo = userSessionInfo;
        _focusIntervalSink = focusIntervalSink;
        _inputEventSink = inputEventSink;
        _wtsNotifier = wtsNotifier;
        _serviceProvider = serviceProvider;
        _logger = logger;
    }

    /// <summary>Lazy-resolve AgentService to avoid circular DI.</summary>
    private AgentService? GetAgentService()
    {
        if (_agentService == null)
        {
            try
            {
                // AgentService is registered as a hosted service (BackgroundService),
                // but also as a singleton in DI for state access
                _agentService = _serviceProvider.GetService(typeof(AgentService)) as AgentService;
            }
            catch
            {
                // Non-fatal: state transitions will just not happen
            }
        }
        return _agentService;
    }

    /// <summary>
    /// Ensure headless analytics collector is running in a user session.
    /// Service (Session 0) launches KaderoAgent.exe --headless via CreateProcessAsUser.
    /// Idempotent: checks if another KaderoAgent already runs in user sessions.
    /// </summary>
    private void EnsureHeadlessCollectorRunning()
    {
        try
        {
            // Check if headless/tray already running in any user session
            var myPid = Environment.ProcessId;
            var agents = System.Diagnostics.Process.GetProcessesByName("KaderoAgent");
            foreach (var p in agents)
            {
                try
                {
                    if (p.Id != myPid && p.SessionId > 0)
                    {
                        _logger.LogDebug("Headless/tray already running: PID={Pid} Session={Session}", p.Id, p.SessionId);
                        return; // Already running in user session
                    }
                }
                finally { p.Dispose(); }
            }

            // No collector in user session — launch one
            var exePath = System.Diagnostics.Process.GetCurrentProcess().MainModule?.FileName
                          ?? Path.Combine(AppContext.BaseDirectory, "KaderoAgent.exe");
            var workDir = Path.GetDirectoryName(exePath);

            _logger.LogInformation("Launching headless collector in user session");
            var pid = Capture.InteractiveProcessLauncher.LaunchInUserSession(
                exePath, "--headless", workDir, _logger);

            if (pid > 0)
                _logger.LogInformation("Headless collector launched: PID={Pid}", pid);
            else
                _logger.LogWarning("Failed to launch headless collector (no user session available)");
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "EnsureHeadlessCollectorRunning failed");
        }
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        // Determine initial session state via WTS query (not just assume true)
        _isSessionActive = CheckSessionActiveViaWts();
        _logger.LogInformation("SessionWatcher started, initial state: active={Active} (via WTS query)", _isSessionActive);

        // Subscribe to WTS session notifications (works in Session 0 without message pump dependency)
        _wtsNotifier.SessionChanged += OnWtsSessionChanged;
        _wtsNotifier.Start();

        try
        {
            // Keep running until cancelled
            await Task.Delay(Timeout.Infinite, stoppingToken);
        }
        catch (OperationCanceledException) { }
        finally
        {
            _wtsNotifier.SessionChanged -= OnWtsSessionChanged;
            _wtsNotifier.Dispose();
            _logger.LogInformation("SessionWatcher stopped");
        }
    }

    /// <summary>
    /// Check if there is an active (unlocked) user session via WTS API.
    /// Used for initial state detection and polling fallback.
    /// </summary>
    public bool CheckSessionActiveViaWts()
    {
        try
        {
            var sessions = _userSessionInfo.GetAllActiveUserSessions();
            // GetAllActiveUserSessions returns sessions with state WTSActive or WTSConnected.
            // WTSActive = unlocked, interactive. WTSConnected = connected but may be locked.
            // If at least one session has a real user, consider session active.
            return sessions.Count > 0;
        }
        catch (Exception ex)
        {
            _logger.LogDebug(ex, "WTS session check failed, assuming active");
            return true;
        }
    }

    private async void OnWtsSessionChanged(object? sender, WtsSessionNotifier.SessionChangeEventArgs e)
    {
        try
        {
            await _lock.WaitAsync();
            try
            {
                switch (e.Event)
                {
                    case WtsSessionNotifier.SessionEvent.Lock:
                        _logger.LogInformation("Session LOCKED (WTS, sessionId={SessionId})", e.SessionId);
                        _isSessionActive = false;
                        _sink.Publish(new AuditEvent
                        {
                            EventType = "SESSION_LOCK",
                            SessionId = _commandHandler.CurrentSessionId,
                            Details = new Dictionary<string, object> { ["reason"] = "SessionLock" }
                        });
                        await _commandHandler.PauseRecordingAsync(CancellationToken.None);
                        GetAgentService()?.SetState(AgentState.Idle);
                        break;

                    case WtsSessionNotifier.SessionEvent.Unlock:
                        _logger.LogInformation("Session UNLOCKED (WTS, sessionId={SessionId})", e.SessionId);
                        _isSessionActive = true;
                        _sink.Publish(new AuditEvent
                        {
                            EventType = "SESSION_UNLOCK",
                            Details = new Dictionary<string, object> { ["reason"] = "SessionUnlock" }
                        });
                        {
                            var creds = _credentialStore.Load();
                            var baseUrl = creds?.ServerUrl?.TrimEnd('/') ?? "";
                            await _commandHandler.ResumeRecordingAsync(baseUrl, CancellationToken.None);

                            // Set state based on whether recording actually started
                            var svc = GetAgentService();
                            if (svc != null)
                            {
                                var serverCfg = _authManager.ServerConfig;
                                var autoStart = serverCfg?.AutoStart ?? true;
                                var configFromServer = serverCfg?.ConfigReceivedFromServer ?? false;

                                if (autoStart && configFromServer && _captureManager.IsRecording)
                                    svc.SetState(AgentState.Recording);
                                else
                                    svc.SetState(AgentState.Online);
                            }
                        }
                        break;

                    case WtsSessionNotifier.SessionEvent.Logon:
                        _logger.LogInformation("Session LOGON (WTS, sessionId={SessionId})", e.SessionId);
                        _isSessionActive = true;

                        // Launch headless collector in the new user session
                        EnsureHeadlessCollectorRunning();

                        // Invalidate cached username -- new user session may be a different user
                        _userSessionInfo.InvalidateCache();

                        _sink.Publish(new AuditEvent
                        {
                            EventType = "SESSION_LOGON",
                            Details = new Dictionary<string, object> { ["reason"] = "SessionLogon" }
                        });

                        // Update FocusIntervalSink and InputEventSink with new username
                        {
                            var newUsername = _userSessionInfo.GetCurrentUsername();
                            _focusIntervalSink.SetUsername(newUsername);
                            _inputEventSink.SetUsername(newUsername);
                            _logger.LogInformation("Session logon: username updated to {Username}", newUsername);
                        }

                        // Transition to Online first (user is present)
                        GetAgentService()?.SetState(AgentState.Online);

                        // Wait for AuthManager to be ready before starting recording.
                        // After system reboot, AgentService may still be retrying auth (network not ready).
                        // Without this wait, HandleSessionLogonAsync would fail because ServerConfig is null.
                        {
                            var waited = 0;
                            while (!_authManager.IsAuthenticated && waited < 60)
                            {
                                _logger.LogDebug("SessionLogon: waiting for AuthManager ({Waited}s)...", waited);
                                await Task.Delay(2000);
                                waited += 2;
                            }
                            if (!_authManager.IsAuthenticated)
                            {
                                _logger.LogWarning("SessionLogon: AuthManager not ready after 60s, skipping auto-start. AgentService will handle it.");
                                break;
                            }
                        }

                        // Start recording for the new user session (uses local defaults if server config not yet received)
                        {
                            {
                                var creds = _credentialStore.Load();
                                var baseUrl = creds?.ServerUrl?.TrimEnd('/') ?? "";
                                await _commandHandler.HandleSessionLogonAsync(baseUrl, CancellationToken.None);

                                // Update state based on result
                                var svc = GetAgentService();
                                if (svc != null)
                                {
                                    if (_captureManager.IsRecording)
                                        svc.SetState(AgentState.Recording);
                                    else
                                        svc.SetState(AgentState.Online);
                                }
                            }
                        }
                        break;

                    case WtsSessionNotifier.SessionEvent.ConsoleConnect:
                    case WtsSessionNotifier.SessionEvent.RemoteConnect:
                        _logger.LogInformation("Session {Event} (WTS, sessionId={SessionId})", e.Event, e.SessionId);
                        _isSessionActive = true;

                        // Invalidate cached username for reconnect scenarios
                        _userSessionInfo.InvalidateCache();

                        _sink.Publish(new AuditEvent
                        {
                            EventType = e.Event == WtsSessionNotifier.SessionEvent.ConsoleConnect
                                ? "CONSOLE_CONNECT" : "REMOTE_CONNECT",
                            Details = new Dictionary<string, object> { ["reason"] = e.Event.ToString() }
                        });

                        // Desktop is back — clear DesktopUnavailable flag so auto-start retries immediately
                        _captureManager.ClearDesktopUnavailable();

                        // Ensure headless collector is running in user session
                        EnsureHeadlessCollectorRunning();

                        // Update FocusIntervalSink and InputEventSink username and resume if paused
                        {
                            var reconnectUsername = _userSessionInfo.GetCurrentUsername();
                            _focusIntervalSink.SetUsername(reconnectUsername);
                            _inputEventSink.SetUsername(reconnectUsername);
                        }
                        {
                            var creds = _credentialStore.Load();
                            var baseUrl = creds?.ServerUrl?.TrimEnd('/') ?? "";

                            // If was in DesktopUnavailable — attempt auto-start directly
                            var svc = GetAgentService();
                            if (svc?.CurrentState == AgentState.DesktopUnavailable)
                            {
                                _logger.LogInformation("Desktop reconnected, auto-starting recording after DesktopUnavailable");
                                await _commandHandler.AutoStartRecordingAsync(baseUrl, CancellationToken.None);
                            }
                            else
                            {
                                await _commandHandler.ResumeRecordingAsync(baseUrl, CancellationToken.None);
                            }

                            if (svc != null)
                            {
                                if (_captureManager.IsRecording)
                                    svc.SetState(AgentState.Recording);
                                else
                                    svc.SetState(AgentState.Online);
                            }
                        }
                        break;

                    case WtsSessionNotifier.SessionEvent.Logoff:
                        _logger.LogInformation("Session LOGOFF (WTS, sessionId={SessionId})", e.SessionId);
                        _isSessionActive = false;
                        _sink.Publish(new AuditEvent
                        {
                            EventType = "SESSION_LOGOFF",
                            SessionId = _commandHandler.CurrentSessionId,
                            Details = new Dictionary<string, object> { ["reason"] = "SessionLogoff" }
                        });
                        await _commandHandler.PauseRecordingAsync(CancellationToken.None);
                        GetAgentService()?.SetState(AgentState.Idle);
                        break;
                }
            }
            finally
            {
                _lock.Release();
            }
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error handling WTS session event: {Event}", e.Event);
        }
    }
}
