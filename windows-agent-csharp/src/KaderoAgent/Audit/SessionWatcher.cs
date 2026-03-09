using KaderoAgent.Capture;
using KaderoAgent.Command;
using KaderoAgent.Auth;
using KaderoAgent.Service;
using KaderoAgent.Storage;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using Microsoft.Win32;

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

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        // Subscribe to session switch events
        SystemEvents.SessionSwitch += OnSessionSwitch;

        _logger.LogInformation("SessionWatcher started, initial state: active={Active}", _isSessionActive);

        try
        {
            // Keep running until cancelled
            await Task.Delay(Timeout.Infinite, stoppingToken);
        }
        catch (OperationCanceledException) { }
        finally
        {
            SystemEvents.SessionSwitch -= OnSessionSwitch;
            _logger.LogInformation("SessionWatcher stopped");
        }
    }

    private async void OnSessionSwitch(object? sender, SessionSwitchEventArgs e)
    {
        try
        {
            await _lock.WaitAsync();
            try
            {
                switch (e.Reason)
                {
                    case SessionSwitchReason.SessionLock:
                        _logger.LogInformation("Session LOCKED");
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

                    case SessionSwitchReason.SessionUnlock:
                        _logger.LogInformation("Session UNLOCKED");
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
                                var autoStart = serverCfg?.AutoStart ?? false;
                                var configFromServer = serverCfg?.ConfigReceivedFromServer ?? false;

                                if (autoStart && configFromServer && _captureManager.IsRecording)
                                    svc.SetState(AgentState.Recording);
                                else
                                    svc.SetState(AgentState.Online);
                            }
                        }
                        break;

                    case SessionSwitchReason.SessionLogon:
                        _logger.LogInformation("Session LOGON");
                        _isSessionActive = true;

                        // Invalidate cached username -- new user session may be a different user
                        _userSessionInfo.InvalidateCache();

                        _sink.Publish(new AuditEvent
                        {
                            EventType = "SESSION_LOGON",
                            Details = new Dictionary<string, object> { ["reason"] = "SessionLogon" }
                        });

                        // Update FocusIntervalSink with new username
                        {
                            var newUsername = _userSessionInfo.GetCurrentUsername();
                            _focusIntervalSink.SetUsername(newUsername);
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

                        // Start recording for the new user session (if autostart + config from server)
                        {
                            var serverCfg = _authManager.ServerConfig;
                            var configFromServer = serverCfg?.ConfigReceivedFromServer ?? false;

                            if (!configFromServer)
                            {
                                _logger.LogInformation("SessionLogon: ServerConfig not confirmed by server, staying Online. " +
                                    "Heartbeat will deliver config and trigger auto-start if needed.");
                            }
                            else
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

                    case SessionSwitchReason.ConsoleConnect:
                    case SessionSwitchReason.RemoteConnect:
                        _logger.LogInformation("Session {Reason}", e.Reason);
                        _isSessionActive = true;

                        // Invalidate cached username for reconnect scenarios
                        _userSessionInfo.InvalidateCache();

                        _sink.Publish(new AuditEvent
                        {
                            EventType = e.Reason == SessionSwitchReason.ConsoleConnect
                                ? "CONSOLE_CONNECT" : "REMOTE_CONNECT",
                            Details = new Dictionary<string, object> { ["reason"] = e.Reason.ToString() }
                        });

                        // Update FocusIntervalSink username and resume if paused
                        {
                            var reconnectUsername = _userSessionInfo.GetCurrentUsername();
                            _focusIntervalSink.SetUsername(reconnectUsername);
                        }
                        {
                            var creds = _credentialStore.Load();
                            var baseUrl = creds?.ServerUrl?.TrimEnd('/') ?? "";
                            await _commandHandler.ResumeRecordingAsync(baseUrl, CancellationToken.None);

                            var svc = GetAgentService();
                            if (svc != null)
                            {
                                if (_captureManager.IsRecording)
                                    svc.SetState(AgentState.Recording);
                                else
                                    svc.SetState(AgentState.Online);
                            }
                        }
                        break;

                    case SessionSwitchReason.SessionLogoff:
                        _logger.LogInformation("Session LOGOFF");
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
            _logger.LogError(ex, "Error handling session switch event: {Reason}", e.Reason);
        }
    }
}
