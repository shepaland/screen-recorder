using System.Text.Json;
using KaderoAgent.Storage;
using Microsoft.Extensions.Logging;

namespace KaderoAgent.Audit;

/// <summary>
/// Sink for input events (mouse clicks, keyboard metrics, scroll, clipboard).
/// Persists to SQLite. Upload handled by DataSyncService.
/// </summary>
public class InputEventSink
{
    private readonly LocalDatabase _db;
    private readonly ILogger<InputEventSink> _logger;

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.SnakeCaseLower,
        DefaultIgnoreCondition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull
    };

    private string? _currentUsername;

    public InputEventSink(
        LocalDatabase db,
        ILogger<InputEventSink> logger)
    {
        _db = db;
        _logger = logger;
    }

    public void SetUsername(string? username) => _currentUsername = username;

    public void Enqueue(InputEvent evt)
    {
        try
        {
            string dataType = evt.EventType switch
            {
                "mouse_click" => "MOUSE_CLICK",
                "keyboard_metric" => "KEYBOARD_METRIC",
                "scroll" => "SCROLL",
                "clipboard" => "CLIPBOARD",
                _ => "MOUSE_CLICK"
            };

            var payload = JsonSerializer.Serialize(evt, JsonOptions);
            _db.InsertActivity(
                eventId: evt.Id,
                dataType: dataType,
                sessionId: evt.SessionId,
                payload: payload);
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Failed to persist input event to SQLite");
        }
    }

    public void EnqueueBatch(IEnumerable<InputEvent> events)
    {
        foreach (var evt in events)
            Enqueue(evt);
    }
}
