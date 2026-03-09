namespace KaderoAgent.Configuration;

public class ServerConfig
{
    public int HeartbeatIntervalSec { get; set; }
    public int SegmentDurationSec { get; set; }
    /// <summary>
    /// Capture FPS from server. 0 means "not set" — agent falls back to AgentConfig.CaptureFps (1 FPS).
    /// Server offline default is 1 FPS. Never allow values below 1 in capture pipeline.
    /// </summary>
    public int CaptureFps { get; set; }
    public string Quality { get; set; } = "";
    public string IngestBaseUrl { get; set; } = "";
    public string ControlPlaneBaseUrl { get; set; } = "";
    public string? Resolution { get; set; }
    public int? SessionMaxDurationHours { get; set; }
    public bool? AutoStart { get; set; }
}
