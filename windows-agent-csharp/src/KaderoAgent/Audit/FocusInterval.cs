namespace KaderoAgent.Audit;

public class FocusInterval
{
    public string Id { get; set; } = Guid.NewGuid().ToString();
    public string ProcessName { get; set; } = "";
    public string WindowTitle { get; set; } = "";
    public bool IsBrowser { get; set; }
    public string? BrowserName { get; set; }
    public string? Domain { get; set; }
    public DateTime StartedAt { get; set; }
    public DateTime? EndedAt { get; set; }
    public int DurationMs { get; set; }
    public string? SessionId { get; set; }

    // Window geometry
    public int? WindowX { get; set; }
    public int? WindowY { get; set; }
    public int? WindowWidth { get; set; }
    public int? WindowHeight { get; set; }
    public bool? IsMaximized { get; set; }
    public bool? IsFullscreen { get; set; }
    public int? MonitorIndex { get; set; }
}
