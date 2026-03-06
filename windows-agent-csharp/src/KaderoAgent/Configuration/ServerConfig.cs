namespace KaderoAgent.Configuration;

public class ServerConfig
{
    public int HeartbeatIntervalSec { get; set; }
    public int SegmentDurationSec { get; set; }
    public int CaptureFps { get; set; }
    public string Quality { get; set; } = "";
    public string IngestBaseUrl { get; set; } = "";
    public string ControlPlaneBaseUrl { get; set; } = "";
    public string? Resolution { get; set; }
    public int? SessionMaxDurationHours { get; set; }
    public bool? AutoStart { get; set; }
}
