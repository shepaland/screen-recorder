namespace KaderoAgent.Audit;

public interface IAuditEventSink
{
    void Publish(AuditEvent evt);
    int QueuedCount { get; }
}
