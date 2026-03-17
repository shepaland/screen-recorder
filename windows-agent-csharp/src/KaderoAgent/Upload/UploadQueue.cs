using System.Threading.Channels;
using KaderoAgent.Storage;
using Microsoft.Extensions.Logging;

namespace KaderoAgent.Upload;

public class UploadQueue
{
    private readonly Channel<SegmentInfo> _channel;
    private readonly SegmentUploader _uploader;
    private readonly SessionManager _sessionManager;
    private readonly LocalDatabase _db;
    private readonly ILogger<UploadQueue> _logger;
    private Task? _processingTask;
    private Task? _retryTask;
    private CancellationTokenSource? _cts;

    public int QueuedCount => _channel.Reader.Count;

    public UploadQueue(SegmentUploader uploader, SessionManager sessionManager,
        LocalDatabase db, ILogger<UploadQueue> logger)
    {
        _channel = Channel.CreateBounded<SegmentInfo>(new BoundedChannelOptions(1000)
        {
            FullMode = BoundedChannelFullMode.Wait
        });
        _uploader = uploader;
        _sessionManager = sessionManager;
        _db = db;
        _logger = logger;
    }

    public async Task EnqueueAsync(SegmentInfo segment)
    {
        await _channel.Writer.WriteAsync(segment);
    }

    public void StartProcessing(string serverUrl, CancellationToken ct)
    {
        _cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        _processingTask = Task.Run(() => ProcessLoop(serverUrl, _cts.Token), _cts.Token);
        _retryTask = Task.Run(() => RetryPendingLoop(serverUrl, _cts.Token), _cts.Token);
    }

    public void StopProcessing()
    {
        _cts?.Cancel();
    }

    private async Task ProcessLoop(string serverUrl, CancellationToken ct)
    {
        await foreach (var segment in _channel.Reader.ReadAllAsync(ct))
        {
            // If current session was invalidated, create a new one
            if (_sessionManager.CurrentSessionId == null)
            {
                try
                {
                    _logger.LogInformation("No active session. Creating new session...");
                    await _sessionManager.StartSessionAsync(ct: ct);
                    _logger.LogInformation("New session created: {SessionId}", _sessionManager.CurrentSessionId);
                }
                catch (Exception ex)
                {
                    _logger.LogError(ex, "Failed to create new session. Segment queued for retry.");
                    _db.SavePendingSegment(segment);
                    await Task.Delay(10_000, ct);
                    continue;
                }
            }

            // Use current session for upload (may differ from segment's original session)
            var effectiveSessionId = _sessionManager.CurrentSessionId ?? segment.SessionId;

            var success = await _uploader.UploadSegmentAsync(
                segment.FilePath, effectiveSessionId, segment.SequenceNum, serverUrl, ct);

            if (success)
            {
                _db.MarkSegmentUploaded(segment.FilePath);
                try { File.Delete(segment.FilePath); } catch { }
            }
            else
            {
                _db.SavePendingSegment(segment);
                _logger.LogWarning("Segment queued for retry: {File}", segment.FilePath);
                // Backoff to prevent hot retry loops
                await Task.Delay(5000, ct);
            }
        }
    }

    /// <summary>
    /// Periodically check SQLite for pending segments and re-enqueue them.
    /// Handles segments that failed during runtime (not just on startup).
    /// </summary>
    private async Task RetryPendingLoop(string serverUrl, CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            try
            {
                await Task.Delay(60_000, ct);

                var pending = _db.GetPendingSegments();
                if (pending.Count == 0) continue;

                _logger.LogInformation("RetryPendingLoop: found {Count} pending segments", pending.Count);
                foreach (var seg in pending)
                {
                    if (ct.IsCancellationRequested) break;
                    if (File.Exists(seg.FilePath))
                        await EnqueueAsync(seg);
                    else
                        _db.MarkSegmentUploaded(seg.FilePath);
                }
            }
            catch (OperationCanceledException) { break; }
            catch (Exception ex)
            {
                _logger.LogWarning(ex, "RetryPendingLoop error, will retry next cycle");
            }
        }
    }
}

public class SegmentInfo
{
    public string FilePath { get; set; } = "";
    public string SessionId { get; set; } = "";
    public int SequenceNum { get; set; }
}
