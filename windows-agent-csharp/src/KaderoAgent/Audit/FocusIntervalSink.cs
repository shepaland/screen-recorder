using System.Text.Json;
using KaderoAgent.Storage;
using Microsoft.Extensions.Logging;

namespace KaderoAgent.Audit;

public class FocusIntervalSink
{
    private readonly LocalDatabase _db;
    private readonly ILogger<FocusIntervalSink> _logger;

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.SnakeCaseLower,
        DefaultIgnoreCondition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull
    };

    private string? _currentUsername;

    public FocusIntervalSink(
        LocalDatabase db,
        ILogger<FocusIntervalSink> logger)
    {
        _db = db;
        _logger = logger;
    }

    public void SetUsername(string? username) => _currentUsername = username;

    public void Enqueue(FocusInterval interval)
    {
        try
        {
            var payload = JsonSerializer.Serialize(interval, JsonOptions);
            _db.InsertActivity(
                eventId: interval.Id,
                dataType: "FOCUS_INTERVAL",
                sessionId: interval.SessionId,
                payload: payload);
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Failed to persist focus interval to SQLite");
        }
    }
}
