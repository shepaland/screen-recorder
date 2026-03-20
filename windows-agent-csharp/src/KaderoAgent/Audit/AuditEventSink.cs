using System.Text.Json;
using KaderoAgent.Storage;
using Microsoft.Extensions.Logging;

namespace KaderoAgent.Audit;

public class AuditEventSink : IAuditEventSink
{
    private readonly LocalDatabase _db;
    private readonly ILogger<AuditEventSink> _logger;

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.SnakeCaseLower,
        DefaultIgnoreCondition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull
    };

    public int QueuedCount => 0; // No RAM queue anymore

    public AuditEventSink(
        LocalDatabase db,
        ILogger<AuditEventSink> logger)
    {
        _db = db;
        _logger = logger;
    }

    public void Publish(AuditEvent evt)
    {
        try
        {
            var payload = JsonSerializer.Serialize(new
            {
                event_type = evt.EventType,
                event_ts = evt.EventTs,
                session_id = evt.SessionId,
                details = evt.Details
            }, JsonOptions);

            _db.InsertActivity(
                eventId: evt.Id,
                dataType: "AUDIT_EVENT",
                sessionId: evt.SessionId,
                payload: payload);
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Failed to persist audit event to pending_activity");
        }
    }
}
