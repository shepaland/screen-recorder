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
}
