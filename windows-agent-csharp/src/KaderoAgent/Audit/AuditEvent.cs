namespace KaderoAgent.Audit;

public class AuditEvent
{
    public string Id { get; set; } = Guid.NewGuid().ToString();
    public string EventType { get; set; } = "";  // SESSION_LOCK, SESSION_UNLOCK, PROCESS_START, PROCESS_STOP, SESSION_LOGON, SESSION_LOGOFF
    public DateTime EventTs { get; set; } = DateTime.UtcNow;
    public string? SessionId { get; set; }
    public Dictionary<string, object> Details { get; set; } = new();
}
