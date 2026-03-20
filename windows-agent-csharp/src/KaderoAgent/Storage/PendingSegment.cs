namespace KaderoAgent.Storage;

public class PendingSegment
{
    public int Id { get; set; }
    public string FilePath { get; set; } = "";
    public string SessionId { get; set; } = "";
    public int SequenceNum { get; set; }
    public long SizeBytes { get; set; }
    public string? ChecksumSha256 { get; set; }
    public int? DurationMs { get; set; }
    public string? RecordedAt { get; set; }
    public string Status { get; set; } = "NEW";
    public string? SegmentId { get; set; }
    public int RetryCount { get; set; }
    public string? LastError { get; set; }
    public DateTime CreatedTs { get; set; }
    public DateTime UpdatedTs { get; set; }
}
