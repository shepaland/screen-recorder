using System.Collections.Concurrent;
using System.Text.Json;
using KaderoAgent.Auth;
using KaderoAgent.Storage;
using KaderoAgent.Util;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace KaderoAgent.Audit;

public class AuditEventSink : BackgroundService, IAuditEventSink
{
    private readonly ConcurrentQueue<AuditEvent> _buffer = new();
    private readonly LocalDatabase _db;
    private readonly ApiClient _apiClient;
    private readonly AuthManager _authManager;
    private readonly ILogger<AuditEventSink> _logger;

    private const int FlushThreshold = 50;
    private const int FlushIntervalSeconds = 30;
    private const int MaxBatchSize = 100;

    public int QueuedCount => _buffer.Count;

    public AuditEventSink(
        LocalDatabase db,
        ApiClient apiClient,
        AuthManager authManager,
        ILogger<AuditEventSink> logger)
    {
        _db = db;
        _apiClient = apiClient;
        _authManager = authManager;
        _logger = logger;
    }

    public void Publish(AuditEvent evt)
    {
        _buffer.Enqueue(evt);

        // Also persist to SQLite for offline tolerance
        try
        {
            _db.SaveAuditEvent(evt);
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Failed to save audit event to SQLite");
        }
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        _logger.LogInformation("AuditEventSink started");

        // First, upload any pending events from previous runs
        await UploadPendingFromDb(stoppingToken);

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
                _logger.LogError(ex, "Error in AuditEventSink flush loop");
            }
        }

        // Final flush on shutdown
        try { await FlushAsync(CancellationToken.None); } catch { }
        _logger.LogInformation("AuditEventSink stopped");
    }

    private async Task FlushAsync(CancellationToken ct)
    {
        // Drain buffer
        var events = new List<AuditEvent>();
        while (_buffer.TryDequeue(out var evt) && events.Count < MaxBatchSize)
        {
            events.Add(evt);
        }

        if (events.Count == 0)
        {
            // Check SQLite for pending events
            await UploadPendingFromDb(ct);
            return;
        }

        await UploadBatch(events, ct);
    }

    private async Task UploadPendingFromDb(CancellationToken ct)
    {
        try
        {
            var pending = _db.GetPendingAuditEvents(MaxBatchSize);
            if (pending.Count == 0) return;

            await UploadBatch(pending, ct);
        }
        catch (Exception ex)
        {
            _logger.LogDebug(ex, "Failed to upload pending audit events from DB");
        }
    }

    private async Task UploadBatch(List<AuditEvent> events, CancellationToken ct)
    {
        var deviceId = _authManager.DeviceId;
        if (string.IsNullOrEmpty(deviceId))
        {
            _logger.LogDebug("No device ID, skipping audit event upload");
            return;
        }

        var serverConfig = _authManager.ServerConfig;
        var baseUrl = serverConfig?.IngestBaseUrl;
        if (string.IsNullOrEmpty(baseUrl))
        {
            _logger.LogDebug("No ingest base URL, skipping audit event upload");
            return;
        }

        var url = $"{baseUrl}/api/v1/ingest/audit-events";

        var body = new
        {
            device_id = deviceId,
            events = events.Select(e => new
            {
                id = e.Id,
                event_type = e.EventType,
                event_ts = e.EventTs.ToString("o"),
                session_id = e.SessionId,
                details = e.Details
            }).ToList()
        };

        try
        {
            var response = await _apiClient.PostAsync<AuditUploadResponse>(url, body, ct);
            _logger.LogInformation("Audit events uploaded: accepted={Accepted}, duplicates={Duplicates}",
                response?.Accepted ?? 0, response?.Duplicates ?? 0);

            // Mark as uploaded in SQLite
            foreach (var evt in events)
            {
                try { _db.MarkAuditEventUploaded(evt.Id); } catch { }
            }
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Failed to upload {Count} audit events, will retry", events.Count);
        }
    }

    private class AuditUploadResponse
    {
        public int Accepted { get; set; }
        public int Duplicates { get; set; }
        public string? CorrelationId { get; set; }
    }
}
