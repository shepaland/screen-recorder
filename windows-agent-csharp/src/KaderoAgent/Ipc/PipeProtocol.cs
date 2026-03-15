namespace KaderoAgent.Ipc;

/// <summary>JSON protocol over Named Pipe. One JSON object per line (\n delimited).</summary>
public static class PipeProtocol
{
    public const string PipeName = "KaderoAgent";
}

public class PipeRequest
{
    public string Command { get; set; } = ""; // "get_status", "reconnect", "report_focus_intervals", "report_input_events"
    public Dictionary<string, string>? Params { get; set; }
    public List<FocusIntervalData>? FocusIntervals { get; set; }
    public List<InputEventData>? InputEvents { get; set; }
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

    // Window geometry (nullable, backwards compatible)
    public int? WindowX { get; set; }
    public int? WindowY { get; set; }
    public int? WindowWidth { get; set; }
    public int? WindowHeight { get; set; }
    public bool? IsMaximized { get; set; }
    public bool? IsFullscreen { get; set; }
    public int? MonitorIndex { get; set; }
}

public class PipeResponse
{
    public bool Success { get; set; }
    public string? Error { get; set; }
    public AgentStatus? Status { get; set; }
}

/// <summary>Input event DTO for tray→service pipe transport.</summary>
public class InputEventData
{
    public string Id { get; set; } = "";
    public string EventType { get; set; } = ""; // "mouse_click", "keyboard_metric", "scroll", "clipboard"
    public string EventTs { get; set; } = "";
    public string? EventEndTs { get; set; }

    // Mouse click
    public int? ClickX { get; set; }
    public int? ClickY { get; set; }
    public string? ClickButton { get; set; }
    public string? ClickType { get; set; }

    // UI element context
    public string? UiElementType { get; set; }
    public string? UiElementName { get; set; }

    // Keyboard metric
    public int? KeystrokeCount { get; set; }
    public bool? HasTypingBurst { get; set; }

    // Scroll
    public string? ScrollDirection { get; set; }
    public int? ScrollTotalDelta { get; set; }
    public int? ScrollEventCount { get; set; }

    // Context
    public string? ProcessName { get; set; }
    public string? WindowTitle { get; set; }

    // Session
    public string? SessionId { get; set; }
    public string? SegmentId { get; set; }
    public int? SegmentOffsetMs { get; set; }
}

public class AgentStatus
{
    public string ConnectionStatus { get; set; } = "disconnected"; // "connected", "disconnected", "error", "reconnecting"
    public string RecordingStatus { get; set; } = "stopped"; // "recording", "stopped", "starting"

    /// <summary>Human-readable agent state for tray display (Russian). E.g. "Запись экрана", "Онлайн".</summary>
    public string AgentStateDisplay { get; set; } = "Запуск...";

    /// <summary>Machine-readable agent state name matching heartbeat status. E.g. "recording", "online", "idle".</summary>
    public string AgentStateName { get; set; } = "starting";

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
    public int SessionMaxDurationMin { get; set; }
    public bool AutoStart { get; set; }
    public bool SessionLocked { get; set; }
    public int AuditEventsQueued { get; set; }
    public string AgentVersion { get; set; } = "1.0.0";

    // Segment context for video timecode binding (Tray reads via get_status)
    public string? CurrentSegmentId { get; set; }
    public string? SegmentStartTs { get; set; } // ISO 8601
    public DateTime? LastHeartbeatTs { get; set; }
    public string? LastError { get; set; }
}
