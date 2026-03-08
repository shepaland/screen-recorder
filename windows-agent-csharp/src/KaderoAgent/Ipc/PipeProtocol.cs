namespace KaderoAgent.Ipc;

/// <summary>JSON protocol over Named Pipe. One JSON object per line (\n delimited).</summary>
public static class PipeProtocol
{
    public const string PipeName = "KaderoAgent";
}

public class PipeRequest
{
    public string Command { get; set; } = ""; // "get_status", "reconnect", "report_focus_intervals"
    public Dictionary<string, string>? Params { get; set; }
    public List<FocusIntervalData>? FocusIntervals { get; set; }
}

/// <summary>Focus interval DTO for tray→service pipe transport.</summary>
public class FocusIntervalData
{
    public string Id { get; set; } = "";
    public string ProcessName { get; set; } = "";
    public string WindowTitle { get; set; } = "";
    public bool IsBrowser { get; set; }
    public string? BrowserName { get; set; }
    public string? Domain { get; set; }
    public string StartedAt { get; set; } = "";
    public string? EndedAt { get; set; }
    public int DurationMs { get; set; }
    public string? SessionId { get; set; }
}

public class PipeResponse
{
    public bool Success { get; set; }
    public string? Error { get; set; }
    public AgentStatus? Status { get; set; }
}

public class AgentStatus
{
    public string ConnectionStatus { get; set; } = "disconnected"; // "connected", "disconnected", "error", "reconnecting"
    public string RecordingStatus { get; set; } = "stopped"; // "recording", "stopped", "starting"
    public string? DeviceId { get; set; }
    public string? ServerUrl { get; set; }
    public int CaptureFps { get; set; }
    public string Quality { get; set; } = "";
    public int SegmentDurationSec { get; set; }
    public int HeartbeatIntervalSec { get; set; }
    public double CpuPercent { get; set; }
    public double MemoryMb { get; set; }
    public double DiskFreeGb { get; set; }
    public int SegmentsQueued { get; set; }
    public string? Resolution { get; set; }
    public int SessionMaxDurationHours { get; set; }
    public bool AutoStart { get; set; }
    public bool SessionLocked { get; set; }
    public int AuditEventsQueued { get; set; }
    public string AgentVersion { get; set; } = "1.0.0";
    public DateTime? LastHeartbeatTs { get; set; }
    public string? LastError { get; set; }
}
