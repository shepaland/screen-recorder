namespace KaderoAgent.Service;

/// <summary>
/// Finite state machine for the agent lifecycle.
/// Each state maps to a heartbeat status string sent to the control-plane.
/// </summary>
public enum AgentState
{
    /// <summary>Service started, pre-auth.</summary>
    Starting,

    /// <summary>Auth in progress, getting ServerConfig.</summary>
    Configuring,

    /// <summary>Auth OK, no active user session (locked / no logon).</summary>
    AwaitingUser,

    /// <summary>User logged in, no recording active.</summary>
    Online,

    /// <summary>Recording disabled by admin via token settings.</summary>
    RecordingDisabled,

    /// <summary>Screen recording active (FFmpeg running).</summary>
    Recording,

    /// <summary>Screen locked or user logged off (recording paused).</summary>
    Idle,

    /// <summary>Recording failure or critical error.</summary>
    Error,

    /// <summary>Service shutting down.</summary>
    Stopped
}

/// <summary>
/// Extension methods for mapping AgentState to heartbeat status strings and tray display messages.
/// </summary>
public static class AgentStateExtensions
{
    /// <summary>
    /// Maps AgentState to the heartbeat status string sent to control-plane.
    /// Must match the server-side CHECK constraint on devices.status.
    /// </summary>
    public static string ToHeartbeatStatus(this AgentState state) => state switch
    {
        AgentState.Starting => "starting",
        AgentState.Configuring => "configuring",
        AgentState.AwaitingUser => "awaiting_user",
        AgentState.Online => "online",
        AgentState.RecordingDisabled => "online",
        AgentState.Recording => "recording",
        AgentState.Idle => "idle",
        AgentState.Error => "error",
        AgentState.Stopped => "stopped",
        _ => "online"
    };

    /// <summary>
    /// Maps AgentState to UI state name for pipe protocol (tray icon switch/case).
    /// Same as heartbeat status except RecordingDisabled maps to "recording_disabled".
    /// </summary>
    public static string ToUiStateName(this AgentState state) => state switch
    {
        AgentState.RecordingDisabled => "recording_disabled",
        _ => state.ToHeartbeatStatus()
    };

    /// <summary>
    /// Maps AgentState to a human-readable Russian status message for the Tray UI.
    /// </summary>
    public static string ToDisplayMessage(this AgentState state) => state switch
    {
        AgentState.Starting => "Запуск...",
        AgentState.Configuring => "Получение конфигурации...",
        AgentState.AwaitingUser => "Ожидание входа пользователя",
        AgentState.Online => "Онлайн",
        AgentState.RecordingDisabled => "Запись отключена администратором",
        AgentState.Recording => "Запись экрана",
        AgentState.Idle => "Пользователь неактивен",
        AgentState.Error => "Ошибка записи",
        AgentState.Stopped => "Остановка...",
        _ => "Неизвестно"
    };
}
