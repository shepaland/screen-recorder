namespace KaderoAgent.Storage;

public class PendingActivity
{
    public int Id { get; set; }
    public string EventId { get; set; } = "";
    public string DataType { get; set; } = "";
    public string? SessionId { get; set; }
    public string Payload { get; set; } = "";
    public string Status { get; set; } = "NEW";
    public string? BatchId { get; set; }
    public int RetryCount { get; set; }
    public string? LastError { get; set; }
    public DateTime CreatedTs { get; set; }
    public DateTime UpdatedTs { get; set; }
}
