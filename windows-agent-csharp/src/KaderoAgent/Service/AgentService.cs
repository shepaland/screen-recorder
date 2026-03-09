using KaderoAgent.Audit;
using KaderoAgent.Auth;
using KaderoAgent.Command;
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
    private readonly CommandHandler _commandHandler;
    private readonly SessionWatcher? _sessionWatcher;
    private readonly ILogger<AgentService> _logger;

    public AgentService(AuthManager authManager, LocalDatabase db, UploadQueue uploadQueue,
        SegmentFileManager fileManager, CredentialStore credentialStore,
        CommandHandler commandHandler, ILogger<AgentService> logger,
        SessionWatcher? sessionWatcher = null)
    {
        _authManager = authManager;
        _db = db;
        _uploadQueue = uploadQueue;
        _fileManager = fileManager;
        _credentialStore = credentialStore;
        _commandHandler = commandHandler;
        _sessionWatcher = sessionWatcher;
        _logger = logger;
    }

    protected override async Task ExecuteAsync(CancellationToken ct)
    {
        _logger.LogInformation("KaderoAgent service starting...");

        // Initialize auth with retry — network may not be ready after system reboot.
        // Exponential backoff: 5, 10, 20, 40, 80, 120, 120... (max 10 attempts, ~10 min total)
        var authAttempt = 0;
        const int maxAuthAttempts = 10;
        while (!ct.IsCancellationRequested)
        {
            var ok = await _authManager.InitializeAsync();
            if (ok) break;

            authAttempt++;
            if (authAttempt >= maxAuthAttempts)
            {
                _logger.LogError("Auth failed after {Attempts} attempts. No valid credentials — please run setup or check network.", authAttempt);
                return;
            }

            var delaySec = Math.Min(5 * (1 << Math.Min(authAttempt - 1, 4)), 120);
            _logger.LogWarning("Auth attempt {Attempt}/{Max} failed, retrying in {Delay}s...",
                authAttempt, maxAuthAttempts, delaySec);
            try { await Task.Delay(TimeSpan.FromSeconds(delaySec), ct); }
            catch (OperationCanceledException) { return; }
        }
        if (ct.IsCancellationRequested) return;

        _logger.LogInformation("Authenticated as device {DeviceId}", _authManager.DeviceId);

        var creds = _credentialStore.Load();
        var baseUrl = creds?.ServerUrl?.TrimEnd('/') ?? "";

        // Auto-start recording FIRST (before pending segments, which can block)
        try
        {
            if (_sessionWatcher != null && !_sessionWatcher.IsSessionActive)
            {
                _logger.LogInformation("Session is locked, skipping auto-start");
            }
            else
            {
                await _commandHandler.AutoStartRecordingAsync(baseUrl, ct);
            }
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Auto-start recording failed, will retry on next cycle");
        }

        // Upload any pending segments from offline buffer (in background)
        var pending = _db.GetPendingSegments();
        if (pending.Count > 0)
        {
            _logger.LogInformation("Found {Count} pending segments, uploading...", pending.Count);
            _uploadQueue.StartProcessing(baseUrl, ct);
            _ = Task.Run(async () =>
            {
                foreach (var seg in pending)
                {
                    if (ct.IsCancellationRequested) break;
                    if (File.Exists(seg.FilePath))
                        await _uploadQueue.EnqueueAsync(seg);
                    else
                        _db.MarkSegmentUploaded(seg.FilePath);
                }
                _logger.LogInformation("Pending segments enqueue complete");
            }, ct);
        }

        // Main loop - periodic maintenance + monitoring
        while (!ct.IsCancellationRequested)
        {
            try
            {
                // Evict old segments when buffer exceeds limit
                _fileManager.EvictOldSegments();

                // Check if FFmpeg crashed and restart
                await _commandHandler.CheckAndRecoverAsync(baseUrl, ct);

                // Check session max duration and rotate
                await _commandHandler.CheckSessionRotationAsync(baseUrl, ct);
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "Maintenance cycle error");
            }

            await Task.Delay(TimeSpan.FromSeconds(30), ct);
        }
    }
}
