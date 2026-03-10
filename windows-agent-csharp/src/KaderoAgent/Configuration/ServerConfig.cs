namespace KaderoAgent.Configuration;

public class ServerConfig
{
    public int HeartbeatIntervalSec { get; set; }
    public int SegmentDurationSec { get; set; }
    /// <summary>
    /// Capture FPS from server. 0 means "not set" -- agent falls back to AgentConfig.CaptureFps (1 FPS).
    /// Server offline default is 1 FPS. Never allow values below 1 in capture pipeline.
    /// </summary>
    public int CaptureFps { get; set; }
    public string Quality { get; set; } = "";
    public string IngestBaseUrl { get; set; } = "";
    public string ControlPlaneBaseUrl { get; set; } = "";
    public string? Resolution { get; set; }

    /// <summary>DEPRECATED: use SessionMaxDurationMin. Kept for backward compat with older servers.</summary>
    public int? SessionMaxDurationHours { get; set; }

    /// <summary>Session max duration in minutes. Takes priority over SessionMaxDurationHours when set.</summary>
    public int? SessionMaxDurationMin { get; set; }

    public bool? AutoStart { get; set; }

    /// <summary>
    /// When false, the agent must NOT capture screen. Heartbeat, focus tracking, and audit events continue.
    /// Controlled by the recording_enabled flag on the device registration token.
    /// Default is true for backward compatibility with servers that don't send this field.
    /// </summary>
    public bool RecordingEnabled { get; set; } = true;

    /// <summary>
    /// True if this config was received from a real server response (device-login or heartbeat).
    /// False if the config is a default or was loaded from cache without server confirmation.
    /// Used to guard autostart: only auto-start recording if the server explicitly confirmed the config.
    /// </summary>
    public bool ConfigReceivedFromServer { get; set; }

    /// <summary>
    /// Resolves the effective session max duration in minutes.
    /// Priority: SessionMaxDurationMin > SessionMaxDurationHours * 60 > fallback.
    /// </summary>
    public int GetEffectiveSessionMaxDurationMin(int fallbackMin = 60)
    {
        if (SessionMaxDurationMin.HasValue && SessionMaxDurationMin.Value > 0)
            return SessionMaxDurationMin.Value;
        if (SessionMaxDurationHours.HasValue && SessionMaxDurationHours.Value > 0)
            return SessionMaxDurationHours.Value * 60;
        return fallbackMin;
    }
}
