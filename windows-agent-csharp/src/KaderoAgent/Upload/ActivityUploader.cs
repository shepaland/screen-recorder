using System.Globalization;
using System.Text.Json;
using KaderoAgent.Audit;
using KaderoAgent.Auth;
using KaderoAgent.Storage;
using KaderoAgent.Util;
using Microsoft.Extensions.Logging;

namespace KaderoAgent.Upload;

public class ActivityUploader
{
    private readonly ApiClient _apiClient;
    private readonly AuthManager _authManager;
    private readonly LocalDatabase _db;
    private readonly UserSessionInfo _userSessionInfo;
    private readonly ILogger<ActivityUploader> _logger;

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.SnakeCaseLower,
        DefaultIgnoreCondition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull
    };

    public ActivityUploader(
        ApiClient apiClient,
        AuthManager authManager,
        LocalDatabase db,
        UserSessionInfo userSessionInfo,
        ILogger<ActivityUploader> logger)
    {
        _apiClient = apiClient;
        _authManager = authManager;
        _db = db;
        _userSessionInfo = userSessionInfo;
        _logger = logger;
    }

    private string? IngestBaseUrl => _authManager.ServerConfig?.IngestBaseUrl;
    private string? DeviceId => _authManager.DeviceId;

    public async Task<bool> SendFocusIntervalsAsync(CancellationToken ct)
    {
        var items = _db.GetPendingActivity("FOCUS_INTERVAL", limit: 100);
        if (items.Count == 0) return false; // no data

        var username = _userSessionInfo.GetCurrentUsername();
        if (string.IsNullOrEmpty(username) || string.IsNullOrEmpty(DeviceId) || string.IsNullOrEmpty(IngestBaseUrl))
            return false;

        var ids = items.Select(i => i.Id).ToList();
        _db.UpdateActivityStatus(ids, "QUEUED");

        try
        {
            await _authManager.EnsureValidTokenAsync();

            var intervals = items.Select(i =>
                JsonSerializer.Deserialize<JsonElement>(i.Payload)).ToList();

            var request = new
            {
                device_id = DeviceId,
                username,
                intervals
            };

            var url = $"{IngestBaseUrl}/activity/focus-intervals";
            await _apiClient.PostAsync<JsonElement>(url, request, ct);

            _db.UpdateActivityStatus(ids, "SERVER_SIDE_DONE");
            _logger.LogInformation("Focus intervals sent: {Count}", items.Count);
            return true;
        }
        catch (HttpRequestException ex) when (ex.StatusCode == System.Net.HttpStatusCode.Conflict)
        {
            _db.UpdateActivityStatus(ids, "SERVER_SIDE_DONE");
            return true;
        }
        catch (Exception ex)
        {
            foreach (var id in ids) _db.SetActivityError(id, ex.Message);
            _logger.LogWarning(ex, "Failed to send {Count} focus intervals", items.Count);
            return false;
        }
    }

    public async Task<bool> SendInputEventsAsync(CancellationToken ct)
    {
        var types = new[] { "MOUSE_CLICK", "KEYBOARD_METRIC", "SCROLL", "CLIPBOARD" };
        var items = _db.GetPendingActivity(types, limit: 500);
        if (items.Count == 0) return false;

        var username = _userSessionInfo.GetCurrentUsername();
        if (string.IsNullOrEmpty(username) || string.IsNullOrEmpty(DeviceId) || string.IsNullOrEmpty(IngestBaseUrl))
            return false;

        var ids = items.Select(i => i.Id).ToList();
        _db.UpdateActivityStatus(ids, "QUEUED");

        try
        {
            await _authManager.EnsureValidTokenAsync();

            // Deserialize InputEvent from payload and build server DTOs
            var allEvents = items.Select(i =>
            {
                var evt = JsonSerializer.Deserialize<InputEventPayload>(i.Payload, JsonOptions);
                return (Type: i.DataType, Event: evt!);
            }).ToList();

            var mouseClicks = allEvents.Where(e => e.Type == "MOUSE_CLICK").Select(e => new
            {
                id = e.Event.Id, timestamp = e.Event.EventTs, x = e.Event.ClickX, y = e.Event.ClickY,
                button = e.Event.ClickButton, click_type = e.Event.ClickType,
                process_name = e.Event.ProcessName, window_title = e.Event.WindowTitle,
                session_id = e.Event.SessionId, segment_id = e.Event.SegmentId, segment_offset_ms = e.Event.SegmentOffsetMs,
                ui_element_type = e.Event.UiElementType, ui_element_name = e.Event.UiElementName
            }).ToList();

            var keyboardMetrics = allEvents.Where(e => e.Type == "KEYBOARD_METRIC").Select(e => new
            {
                id = e.Event.Id, interval_start = e.Event.EventTs, interval_end = e.Event.EventEndTs,
                keystroke_count = e.Event.KeystrokeCount, has_typing_burst = e.Event.HasTypingBurst,
                process_name = e.Event.ProcessName, window_title = e.Event.WindowTitle,
                session_id = e.Event.SessionId, segment_id = e.Event.SegmentId, segment_offset_ms = e.Event.SegmentOffsetMs
            }).ToList();

            var scrollEvents = allEvents.Where(e => e.Type == "SCROLL").Select(e => new
            {
                id = e.Event.Id, interval_start = e.Event.EventTs, interval_end = e.Event.EventEndTs,
                direction = e.Event.ScrollDirection, total_delta = e.Event.ScrollTotalDelta, event_count = e.Event.ScrollEventCount,
                process_name = e.Event.ProcessName, session_id = e.Event.SessionId,
                segment_id = e.Event.SegmentId, segment_offset_ms = e.Event.SegmentOffsetMs
            }).ToList();

            var clipboardEvents = allEvents.Where(e => e.Type == "CLIPBOARD").Select(e => new
            {
                id = e.Event.Id, timestamp = e.Event.EventTs, action = "copy",
                source_process = e.Event.ProcessName, content_type = "text", content_length = 0,
                session_id = e.Event.SessionId, segment_id = e.Event.SegmentId, segment_offset_ms = e.Event.SegmentOffsetMs,
                content_hash = e.Event.ContentHash
            }).ToList();

            var request = new
            {
                device_id = DeviceId,
                username,
                mouse_clicks = mouseClicks.Count > 0 ? (object)mouseClicks : null,
                keyboard_metrics = keyboardMetrics.Count > 0 ? (object)keyboardMetrics : null,
                scroll_events = scrollEvents.Count > 0 ? (object)scrollEvents : null,
                clipboard_events = clipboardEvents.Count > 0 ? (object)clipboardEvents : null
            };

            var url = $"{IngestBaseUrl}/activity/input-events";
            await _apiClient.PostAsync<JsonElement>(url, request, ct);

            _db.UpdateActivityStatus(ids, "SERVER_SIDE_DONE");
            _logger.LogInformation("Input events sent: {Count}", items.Count);
            return true;
        }
        catch (HttpRequestException ex) when (ex.StatusCode == System.Net.HttpStatusCode.Conflict)
        {
            _db.UpdateActivityStatus(ids, "SERVER_SIDE_DONE");
            return true;
        }
        catch (HttpRequestException ex) when (ex.StatusCode == System.Net.HttpStatusCode.BadRequest)
        {
            // Discard malformed events to prevent infinite retry
            _db.UpdateActivityStatus(ids, "SERVER_SIDE_DONE");
            _logger.LogWarning(ex, "Discarding {Count} input events (400 Bad Request)", items.Count);
            return true;
        }
        catch (Exception ex)
        {
            foreach (var id in ids) _db.SetActivityError(id, ex.Message);
            _logger.LogWarning(ex, "Failed to send {Count} input events", items.Count);
            return false;
        }
    }

    public async Task<bool> SendAuditEventsAsync(CancellationToken ct)
    {
        var items = _db.GetPendingActivity("AUDIT_EVENT", limit: 100);
        if (items.Count == 0) return false;

        var username = _userSessionInfo.GetCurrentUsername();
        if (string.IsNullOrEmpty(DeviceId) || string.IsNullOrEmpty(IngestBaseUrl))
            return false;

        var ids = items.Select(i => i.Id).ToList();
        _db.UpdateActivityStatus(ids, "QUEUED");

        try
        {
            await _authManager.EnsureValidTokenAsync();

            var events = items.Select(i =>
            {
                var doc = JsonDocument.Parse(i.Payload);
                var root = doc.RootElement;

                // details may be stored as string (legacy) or as object
                object details = new { };
                if (root.TryGetProperty("details", out var d))
                {
                    if (d.ValueKind == JsonValueKind.String)
                    {
                        // Legacy: details was serialized as JSON string
                        try { details = JsonSerializer.Deserialize<Dictionary<string, object>>(d.GetString()!) ?? new Dictionary<string, object>(); }
                        catch { details = new { }; }
                    }
                    else
                    {
                        details = d;
                    }
                }

                return new
                {
                    id = i.EventId,
                    event_type = root.TryGetProperty("event_type", out var et) ? et.GetString() : "",
                    event_ts = root.TryGetProperty("event_ts", out var ts) ? ts.GetString() : DateTime.UtcNow.ToString("o"),
                    session_id = i.SessionId,
                    details
                };
            }).ToList();

            var request = new
            {
                device_id = DeviceId,
                username,
                events
            };

            var url = $"{IngestBaseUrl}/audit-events";
            await _apiClient.PostAsync<JsonElement>(url, request, ct);

            _db.UpdateActivityStatus(ids, "SERVER_SIDE_DONE");
            _logger.LogInformation("Audit events sent: {Count}", items.Count);
            return true;
        }
        catch (HttpRequestException ex) when (ex.StatusCode == System.Net.HttpStatusCode.Conflict)
        {
            _db.UpdateActivityStatus(ids, "SERVER_SIDE_DONE");
            return true;
        }
        catch (Exception ex)
        {
            foreach (var id in ids) _db.SetActivityError(id, ex.Message);
            _logger.LogWarning(ex, "Failed to send {Count} audit events", items.Count);
            return false;
        }
    }

    /// <summary>DTO matching InputEvent agent model for deserialization from SQLite payload.</summary>
    private class InputEventPayload
    {
        public string? Id { get; set; }
        public string? EventType { get; set; }
        public string? EventTs { get; set; }
        public string? EventEndTs { get; set; }
        public int? ClickX { get; set; }
        public int? ClickY { get; set; }
        public string? ClickButton { get; set; }
        public string? ClickType { get; set; }
        public string? UiElementType { get; set; }
        public string? UiElementName { get; set; }
        public int? KeystrokeCount { get; set; }
        public bool? HasTypingBurst { get; set; }
        public string? ScrollDirection { get; set; }
        public int? ScrollTotalDelta { get; set; }
        public int? ScrollEventCount { get; set; }
        public string? ProcessName { get; set; }
        public string? WindowTitle { get; set; }
        public string? SegmentId { get; set; }
        public int? SegmentOffsetMs { get; set; }
        public string? SessionId { get; set; }
        public string? ContentHash { get; set; }
    }
}
