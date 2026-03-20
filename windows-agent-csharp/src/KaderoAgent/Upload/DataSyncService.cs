using System.Security.Cryptography;
using KaderoAgent.Auth;
using KaderoAgent.Storage;
using KaderoAgent.Util;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace KaderoAgent.Upload;

public class DataSyncService : BackgroundService
{
    private readonly ActivityUploader _activityUploader;
    private readonly SegmentUploader _segmentUploader;
    private readonly SessionManager _sessionManager;
    private readonly AuthManager _authManager;
    private readonly CredentialStore _credentialStore;
    private readonly LocalDatabase _db;
    private readonly ILogger<DataSyncService> _logger;

    private volatile bool _serverAvailable;
    private readonly SemaphoreSlim _serverAvailableSignal = new(0, 1);

    public DataSyncService(
        ActivityUploader activityUploader,
        SegmentUploader segmentUploader,
        SessionManager sessionManager,
        AuthManager authManager,
        CredentialStore credentialStore,
        LocalDatabase db,
        ILogger<DataSyncService> logger)
    {
        _activityUploader = activityUploader;
        _segmentUploader = segmentUploader;
        _sessionManager = sessionManager;
        _authManager = authManager;
        _credentialStore = credentialStore;
        _db = db;
        _logger = logger;
    }

    /// <summary>
    /// Called by HeartbeatService on successful heartbeat.
    /// </summary>
    public void SetServerAvailable(bool available)
    {
        _serverAvailable = available;
        if (available && _serverAvailableSignal.CurrentCount == 0)
        {
            try { _serverAvailableSignal.Release(); } catch (SemaphoreFullException) { }
        }
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        _logger.LogInformation("DataSyncService started");

        while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                // Wait until server is available (async, non-blocking)
                await _serverAvailableSignal.WaitAsync(stoppingToken);

                // Round-robin loop: activity batch → 1 video segment → repeat
                bool hasData = true;
                while (_serverAvailable && hasData && !stoppingToken.IsCancellationRequested)
                {
                    // Step A: batch activity (focus + input + audit)
                    bool activitySent = await SendActivityBatchAsync(stoppingToken);
                    if (!_serverAvailable) break;

                    // Step B: 1 video segment
                    bool segmentSent = await SendOneVideoSegmentAsync(stoppingToken);
                    if (!_serverAvailable) break;

                    hasData = activitySent || segmentSent;
                }

                // No data left — cleanup + sleep 5s
                if (_serverAvailable && !stoppingToken.IsCancellationRequested)
                {
                    Cleanup();
                    await Task.Delay(5000, stoppingToken);
                }
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                break;
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "DataSyncService error, pausing 10s");
                try { await Task.Delay(10000, stoppingToken); }
                catch (OperationCanceledException) { break; }
            }
        }

        _logger.LogInformation("DataSyncService stopped");
    }

    private async Task<bool> SendActivityBatchAsync(CancellationToken ct)
    {
        bool anySent = false;

        if (_serverAvailable)
        {
            bool sent = await _activityUploader.SendFocusIntervalsAsync(ct);
            if (sent) anySent = true;
            // Only mark server unavailable on network errors, not on empty data
        }

        if (_serverAvailable)
        {
            bool sent = await _activityUploader.SendInputEventsAsync(ct);
            if (sent) anySent = true;
        }

        if (_serverAvailable)
        {
            bool sent = await _activityUploader.SendAuditEventsAsync(ct);
            if (sent) anySent = true;
        }

        return anySent;
    }

    private async Task<bool> SendOneVideoSegmentAsync(CancellationToken ct)
    {
        var segment = _db.GetNextPendingSegment();
        if (segment == null) return false;

        _db.UpdateSegmentStatus(segment.Id, "QUEUED");

        try
        {
            // Check file exists
            if (!File.Exists(segment.FilePath))
            {
                _db.UpdateSegmentStatus(segment.Id, "EVICTED");
                _logger.LogWarning("Segment file not found, marking EVICTED: {Path}", segment.FilePath);
                return true; // processed (evicted)
            }

            // Use session from SQLite record; if no active session, leave in PENDING
            if (_sessionManager.CurrentSessionId == null)
            {
                _logger.LogDebug("No active session, deferring segment {Id}", segment.Id);
                _db.UpdateSegmentStatus(segment.Id, "PENDING");
                return false;
            }

            var effectiveSessionId = _sessionManager.CurrentSessionId;

            // Compute SHA-256 if not already set
            string checksum = segment.ChecksumSha256 ?? "";
            byte[] fileBytes;
            if (string.IsNullOrEmpty(checksum))
            {
                fileBytes = await File.ReadAllBytesAsync(segment.FilePath, ct);
                checksum = Convert.ToHexString(SHA256.HashData(fileBytes)).ToLowerInvariant();
            }
            else
            {
                fileBytes = await File.ReadAllBytesAsync(segment.FilePath, ct);
            }

            await _authManager.EnsureValidTokenAsync();

            var creds = _credentialStore.Load();
            var baseUrl = creds?.ServerUrl?.TrimEnd('/') ?? "";

            bool success = await _segmentUploader.UploadSegmentAsync(
                segment.FilePath, effectiveSessionId, segment.SequenceNum, baseUrl, ct);

            if (success)
            {
                _db.UpdateSegmentStatus(segment.Id, "SERVER_SIDE_DONE");
                try { File.Delete(segment.FilePath); } catch { }
                _logger.LogDebug("Segment uploaded: id={Id} seq={Seq}", segment.Id, segment.SequenceNum);
                return true;
            }
            else
            {
                _db.SetSegmentError(segment.Id, "Upload failed");
                return false;
            }
        }
        catch (FileNotFoundException)
        {
            _db.UpdateSegmentStatus(segment.Id, "EVICTED");
            return true;
        }
        catch (Exception ex)
        {
            _db.SetSegmentError(segment.Id, ex.Message);
            _logger.LogError(ex, "Failed to upload segment {Id}", segment.Id);
            return false;
        }
    }

    private void Cleanup()
    {
        try
        {
            var maxRetention = TimeSpan.FromHours(72);
            var serverSideDoneAge = TimeSpan.FromHours(1);
            var evictedAge = TimeSpan.FromHours(24);

            int deletedActivity = _db.CleanupActivity(serverSideDoneAge, evictedAge, maxRetention);
            int deletedSegments = _db.CleanupSegments(maxRetention);

            if (deletedActivity > 0 || deletedSegments > 0)
            {
                _logger.LogInformation("Cleanup: {ActivityDeleted} activity, {SegmentsDeleted} segments removed",
                    deletedActivity, deletedSegments);
            }
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Cleanup failed");
        }
    }
}
