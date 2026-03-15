using System.Collections.Concurrent;
using KaderoAgent.Auth;
using KaderoAgent.Util;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace KaderoAgent.Audit;

public class FocusIntervalSink : BackgroundService
{
    private readonly ConcurrentQueue<FocusInterval> _queue = new();
    private readonly ApiClient _apiClient;
    private readonly AuthManager _authManager;
    private readonly ILogger<FocusIntervalSink> _logger;

    private const int FlushIntervalSeconds = 30;
    private const int MaxBatchSize = 100;

    private string? _currentUsername;

    public int QueuedCount => _queue.Count;

    public FocusIntervalSink(
        ApiClient apiClient,
        AuthManager authManager,
        ILogger<FocusIntervalSink> logger)
    {
        _apiClient = apiClient;
        _authManager = authManager;
        _logger = logger;
    }

    public void SetUsername(string? username) => _currentUsername = username;

    public void Enqueue(FocusInterval interval)
    {
        _queue.Enqueue(interval);
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        _logger.LogInformation("FocusIntervalSink started");

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
                _logger.LogError(ex, "Error in FocusIntervalSink flush loop");
            }
        }

        // Final flush on shutdown
        try { await FlushAsync(CancellationToken.None); } catch { }
        _logger.LogInformation("FocusIntervalSink stopped");
    }

    private async Task FlushAsync(CancellationToken ct)
    {
        if (_queue.IsEmpty || _currentUsername == null) return;

        var batch = new List<FocusInterval>();
        while (batch.Count < MaxBatchSize && _queue.TryDequeue(out var item))
        {
            batch.Add(item);
        }

        if (batch.Count == 0) return;

        var deviceId = _authManager.DeviceId;
        if (string.IsNullOrEmpty(deviceId))
        {
            _logger.LogDebug("No device ID, skipping focus interval upload");
            // Re-queue
            foreach (var item in batch) _queue.Enqueue(item);
            return;
        }

        var serverConfig = _authManager.ServerConfig;
        var baseUrl = serverConfig?.IngestBaseUrl;
        if (string.IsNullOrEmpty(baseUrl))
        {
            _logger.LogDebug("No ingest base URL, skipping focus interval upload");
            foreach (var item in batch) _queue.Enqueue(item);
            return;
        }

        var url = $"{baseUrl}/activity/focus-intervals";

        var body = new
        {
            device_id = deviceId,
            username = _currentUsername,
            intervals = batch.Select(i => new
            {
                id = i.Id,
                process_name = i.ProcessName,
                window_title = i.WindowTitle,
                is_browser = i.IsBrowser,
                browser_name = i.BrowserName,
                domain = i.Domain,
                started_at = i.StartedAt.ToString("o"),
                ended_at = i.EndedAt?.ToString("o"),
                duration_ms = i.DurationMs,
                session_id = i.SessionId,
                window_x = i.WindowX,
                window_y = i.WindowY,
                window_width = i.WindowWidth,
                window_height = i.WindowHeight,
                is_maximized = i.IsMaximized,
                is_fullscreen = i.IsFullscreen,
                monitor_index = i.MonitorIndex
            }).ToList()
        };

        try
        {
            var response = await _apiClient.PostAsync<FocusUploadResponse>(url, body, ct);
            _logger.LogInformation("Focus intervals uploaded: accepted={Accepted}, duplicates={Duplicates}",
                response?.Accepted ?? 0, response?.Duplicates ?? 0);
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Failed to upload {Count} focus intervals, re-queuing", batch.Count);
            foreach (var item in batch) _queue.Enqueue(item);
        }
    }

    private class FocusUploadResponse
    {
        public int Accepted { get; set; }
        public int Duplicates { get; set; }
        public string? CorrelationId { get; set; }
    }
}
