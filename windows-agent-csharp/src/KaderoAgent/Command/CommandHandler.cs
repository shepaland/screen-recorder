using KaderoAgent.Auth;
using KaderoAgent.Capture;
using KaderoAgent.Upload;
using KaderoAgent.Util;
using Microsoft.Extensions.Logging;

namespace KaderoAgent.Command;

public class CommandHandler
{
    private readonly ScreenCaptureManager _captureManager;
    private readonly SessionManager _sessionManager;
    private readonly UploadQueue _uploadQueue;
    private readonly AuthManager _authManager;
    private readonly ApiClient _apiClient;
    private readonly ILogger<CommandHandler> _logger;

    public CommandHandler(ScreenCaptureManager captureManager, SessionManager sessionManager,
        UploadQueue uploadQueue, AuthManager authManager, ApiClient apiClient, ILogger<CommandHandler> logger)
    {
        _captureManager = captureManager;
        _sessionManager = sessionManager;
        _uploadQueue = uploadQueue;
        _authManager = authManager;
        _apiClient = apiClient;
        _logger = logger;
    }

    public async Task HandleAsync(PendingCommand cmd, string baseUrl, CancellationToken ct)
    {
        _logger.LogInformation("Handling command: {Type} ({Id})", cmd.CommandType, cmd.Id);

        try
        {
            switch (cmd.CommandType?.ToUpper())
            {
                case "START_RECORDING":
                    await StartRecording(baseUrl, ct);
                    break;
                case "STOP_RECORDING":
                    await StopRecording(ct);
                    break;
                case "UPDATE_SETTINGS":
                    // TODO: update config from payload
                    break;
                case "RESTART_AGENT":
                    Environment.Exit(0); // Service will auto-restart
                    break;
            }

            // Acknowledge
            var ackUrl = $"{baseUrl}/api/cp/v1/devices/commands/{cmd.Id}/ack";
            await _apiClient.PutAsync<object>(ackUrl, new { status = "acknowledged" }, ct);
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Command {Id} failed", cmd.Id);
            var ackUrl = $"{baseUrl}/api/cp/v1/devices/commands/{cmd.Id}/ack";
            await _apiClient.PutAsync<object>(ackUrl, new { status = "failed", result = new { message = ex.Message } }, ct);
        }
    }

    private async Task StartRecording(string baseUrl, CancellationToken ct)
    {
        if (_captureManager.IsRecording) return;

        var sessionId = await _sessionManager.StartSessionAsync(ct);

        var config = _authManager.ServerConfig;
        var fps = config?.CaptureFps ?? 5;
        var segDuration = config?.SegmentDurationSec ?? 10;
        var quality = config?.Quality ?? "medium";

        _captureManager.Start(sessionId, fps, segDuration, quality);
        _uploadQueue.StartProcessing(baseUrl, ct);

        // Start watching for new segments
        _ = Task.Run(() => WatchSegments(sessionId, baseUrl, ct), ct);
    }

    private async Task StopRecording(CancellationToken ct)
    {
        _captureManager.Stop();
        _uploadQueue.StopProcessing();
        await _sessionManager.EndSessionAsync(ct);
    }

    private async Task WatchSegments(string sessionId, string baseUrl, CancellationToken ct)
    {
        var dir = _captureManager.OutputDirectory;
        var sequenceNum = 0;
        var processedFiles = new HashSet<string>();

        while (!ct.IsCancellationRequested && _captureManager.IsRecording)
        {
            await Task.Delay(2000, ct); // Check every 2 seconds

            var files = Directory.GetFiles(dir, "segment_*.mp4")
                .OrderBy(f => f)
                .ToList();

            // Process all complete files (all except the last one being written)
            var completeFiles = files.Count > 1 ? files.Take(files.Count - 1) : Array.Empty<string>();

            foreach (var file in completeFiles)
            {
                if (processedFiles.Contains(file)) continue;
                processedFiles.Add(file);

                await _uploadQueue.EnqueueAsync(new SegmentInfo
                {
                    FilePath = file,
                    SessionId = sessionId,
                    SequenceNum = sequenceNum++
                });
            }
        }

        // Upload remaining files after stop
        await Task.Delay(1000, ct);
        var remaining = Directory.GetFiles(dir, "segment_*.mp4")
            .Where(f => !processedFiles.Contains(f))
            .OrderBy(f => f);

        foreach (var file in remaining)
        {
            await _uploadQueue.EnqueueAsync(new SegmentInfo
            {
                FilePath = file,
                SessionId = sessionId,
                SequenceNum = sequenceNum++
            });
        }
    }
}
