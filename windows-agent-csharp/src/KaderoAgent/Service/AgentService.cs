using KaderoAgent.Auth;
using KaderoAgent.Storage;
using KaderoAgent.Upload;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace KaderoAgent.Service;

public class AgentService : BackgroundService
{
    private readonly AuthManager _authManager;
    private readonly LocalDatabase _db;
    private readonly UploadQueue _uploadQueue;
    private readonly SegmentFileManager _fileManager;
    private readonly CredentialStore _credentialStore;
    private readonly ILogger<AgentService> _logger;

    public AgentService(AuthManager authManager, LocalDatabase db, UploadQueue uploadQueue,
        SegmentFileManager fileManager, CredentialStore credentialStore, ILogger<AgentService> logger)
    {
        _authManager = authManager;
        _db = db;
        _uploadQueue = uploadQueue;
        _fileManager = fileManager;
        _credentialStore = credentialStore;
        _logger = logger;
    }

    protected override async Task ExecuteAsync(CancellationToken ct)
    {
        _logger.LogInformation("KaderoAgent service starting...");

        // Initialize auth
        var ok = await _authManager.InitializeAsync();
        if (!ok)
        {
            _logger.LogWarning("No valid credentials. Please run setup.");
            return;
        }

        _logger.LogInformation("Authenticated as device {DeviceId}", _authManager.DeviceId);

        // Upload any pending segments from offline buffer
        var pending = _db.GetPendingSegments();
        if (pending.Count > 0)
        {
            _logger.LogInformation("Found {Count} pending segments, uploading...", pending.Count);
            var creds = _credentialStore.Load();
            var baseUrl = creds?.ServerUrl?.TrimEnd('/') ?? "";
            _uploadQueue.StartProcessing(baseUrl, ct);
            foreach (var seg in pending)
            {
                if (File.Exists(seg.FilePath))
                    await _uploadQueue.EnqueueAsync(seg);
                else
                    _db.MarkSegmentUploaded(seg.FilePath);
            }
        }

        // Main loop - periodic maintenance
        while (!ct.IsCancellationRequested)
        {
            _fileManager.EvictOldSegments();
            await Task.Delay(TimeSpan.FromMinutes(5), ct);
        }
    }
}
