using System.Collections.Concurrent;
using KaderoAgent.Auth;
using KaderoAgent.Util;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace KaderoAgent.Audit;

/// <summary>
/// Service-side sink for input events (mouse clicks, keyboard metrics, scroll, clipboard).
/// Receives events from Tray via Named Pipe, buffers and uploads in batches.
/// Pattern: same as FocusIntervalSink.
/// </summary>
public class InputEventSink : BackgroundService
{
    private readonly ConcurrentQueue<InputEvent> _queue = new();
    private readonly ApiClient _apiClient;
    private readonly AuthManager _authManager;
    private readonly UserSessionInfo _userSessionInfo;
    private readonly ILogger<InputEventSink> _logger;

    private const int FlushIntervalSeconds = 30;
    private const int MaxBatchSize = 500;

    private string? _currentUsername;

    public int QueuedCount => _queue.Count;

    public InputEventSink(
        ApiClient apiClient,
        AuthManager authManager,
        UserSessionInfo userSessionInfo,
        ILogger<InputEventSink> logger)
    {
        _apiClient = apiClient;
        _authManager = authManager;
        _userSessionInfo = userSessionInfo;
        _logger = logger;
    }

    public void SetUsername(string? username) => _currentUsername = username;

    public void Enqueue(InputEvent evt)
    {
        _queue.Enqueue(evt);
    }

    public void EnqueueBatch(IEnumerable<InputEvent> events)
    {
        foreach (var evt in events)
            _queue.Enqueue(evt);
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        _logger.LogInformation("InputEventSink started");

        while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                await Task.Delay(TimeSpan.FromSeconds(FlushIntervalSeconds), stoppingToken);
                await FlushAsync(stoppingToken);
            }
            catch (OperationCanceledException) { break; }
            catch (Exception ex)
            {
                _logger.LogError(ex, "Error in InputEventSink flush loop");
            }
        }

        try { await FlushAsync(CancellationToken.None); } catch { }
        _logger.LogInformation("InputEventSink stopped");
    }

    private async Task FlushAsync(CancellationToken ct)
    {
        // Auto-resolve username if not set (e.g. service started before user logon)
        if (_currentUsername == null)
        {
            _currentUsername = _userSessionInfo.GetCurrentUsername();
            if (_currentUsername != null)
                _logger.LogInformation("InputEventSink: auto-resolved username to {Username}", _currentUsername);
        }

        if (_queue.IsEmpty || _currentUsername == null) return;

        var batch = new List<InputEvent>();
        while (batch.Count < MaxBatchSize && _queue.TryDequeue(out var item))
        {
            batch.Add(item);
        }

        if (batch.Count == 0) return;

        var deviceId = _authManager.DeviceId;
        if (string.IsNullOrEmpty(deviceId))
        {
            _logger.LogDebug("No device ID, skipping input event upload");
            foreach (var item in batch) _queue.Enqueue(item);
            return;
        }

        var serverConfig = _authManager.ServerConfig;
        var baseUrl = serverConfig?.IngestBaseUrl;
        if (string.IsNullOrEmpty(baseUrl))
        {
            _logger.LogDebug("No ingest base URL, skipping input event upload");
            foreach (var item in batch) _queue.Enqueue(item);
            return;
        }

        var url = $"{baseUrl}/activity/input-events";

        // Split batch by event type for the server DTO
        var mouseClicks = batch.Where(e => e.EventType == "mouse_click").ToList();
        var keyboardMetrics = batch.Where(e => e.EventType == "keyboard_metric").ToList();
        var scrollEvents = batch.Where(e => e.EventType == "scroll").ToList();
        var clipboardEvents = batch.Where(e => e.EventType == "clipboard").ToList();

        var body = new
        {
            device_id = deviceId,
            username = _currentUsername,
            mouse_clicks = mouseClicks.Count > 0 ? mouseClicks.Select(e => new
            {
                id = e.Id,
                timestamp = e.EventTs.ToString("o"),
                x = e.ClickX,
                y = e.ClickY,
                button = e.ClickButton,
                click_type = e.ClickType,
                process_name = e.ProcessName,
                window_title = e.WindowTitle,
                session_id = e.SessionId,
                ui_element_type = e.UiElementType,
                ui_element_name = e.UiElementName,
                segment_id = e.SegmentId,
                segment_offset_ms = e.SegmentOffsetMs
            }).ToList() : null,
            keyboard_metrics = keyboardMetrics.Count > 0 ? keyboardMetrics.Select(e => new
            {
                id = e.Id,
                interval_start = e.EventTs.ToString("o"),
                interval_end = e.EventEndTs?.ToString("o"),
                keystroke_count = e.KeystrokeCount,
                has_typing_burst = e.HasTypingBurst,
                process_name = e.ProcessName,
                window_title = e.WindowTitle,
                session_id = e.SessionId,
                segment_id = e.SegmentId,
                segment_offset_ms = e.SegmentOffsetMs
            }).ToList() : null,
            scroll_events = scrollEvents.Count > 0 ? scrollEvents.Select(e => new
            {
                id = e.Id,
                interval_start = e.EventTs.ToString("o"),
                interval_end = e.EventEndTs?.ToString("o"),
                direction = e.ScrollDirection,
                total_delta = e.ScrollTotalDelta,
                event_count = e.ScrollEventCount,
                process_name = e.ProcessName,
                session_id = e.SessionId,
                segment_id = e.SegmentId,
                segment_offset_ms = e.SegmentOffsetMs
            }).ToList() : null,
            clipboard_events = clipboardEvents.Count > 0 ? clipboardEvents.Select(e => new
            {
                id = e.Id,
                timestamp = e.EventTs.ToString("o"),
                action = e.EventType == "clipboard" ? "copy" : null,
                source_process = e.ProcessName,
                content_type = "text",
                content_length = 0,
                session_id = e.SessionId,
                segment_id = e.SegmentId,
                segment_offset_ms = e.SegmentOffsetMs
            }).ToList() : null
        };

        try
        {
            var response = await _apiClient.PostAsync<InputUploadResponse>(url, body, ct);
            _logger.LogInformation("Input events uploaded: accepted={Accepted}, duplicates={Duplicates}",
                response?.Accepted ?? 0, response?.Duplicates ?? 0);
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Failed to upload {Count} input events, re-queuing", batch.Count);
            foreach (var item in batch) _queue.Enqueue(item);
        }
    }

    private class InputUploadResponse
    {
        public int Accepted { get; set; }
        public int Duplicates { get; set; }
        public string? CorrelationId { get; set; }
    }
}
