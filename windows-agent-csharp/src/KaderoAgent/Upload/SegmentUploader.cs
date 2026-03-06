using System.Security.Cryptography;
using KaderoAgent.Auth;
using KaderoAgent.Capture;
using KaderoAgent.Configuration;
using KaderoAgent.Util;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;

namespace KaderoAgent.Upload;

public class SegmentUploader
{
    private readonly ApiClient _apiClient;
    private readonly AuthManager _authManager;
    private readonly ScreenCaptureManager _captureManager;
    private readonly IOptions<AgentConfig> _config;
    private readonly ILogger<SegmentUploader> _logger;

    public SegmentUploader(ApiClient apiClient, AuthManager authManager,
        ScreenCaptureManager captureManager, IOptions<AgentConfig> config,
        ILogger<SegmentUploader> logger)
    {
        _apiClient = apiClient;
        _authManager = authManager;
        _captureManager = captureManager;
        _config = config;
        _logger = logger;
    }

    public async Task<bool> UploadSegmentAsync(string filePath, string sessionId, int sequenceNum,
        string serverUrl, CancellationToken ct = default)
    {
        try
        {
            await _authManager.EnsureValidTokenAsync();

            var fileBytes = await File.ReadAllBytesAsync(filePath, ct);
            var checksum = Convert.ToHexString(SHA256.HashData(fileBytes)).ToLower();
            var baseUrl = serverUrl.TrimEnd('/');

            // Use actual recording settings, not hardcoded values
            var serverCfg = _authManager.ServerConfig;
            var fps = _captureManager.CurrentFps > 0
                ? _captureManager.CurrentFps
                : (serverCfg?.CaptureFps > 0 ? serverCfg.CaptureFps : _config.Value.CaptureFps);
            var resolution = !string.IsNullOrEmpty(_captureManager.CurrentResolution)
                ? _captureManager.CurrentResolution
                : (!string.IsNullOrEmpty(serverCfg?.Resolution) ? serverCfg.Resolution : _config.Value.Resolution);
            var segDuration = serverCfg?.SegmentDurationSec > 0
                ? serverCfg.SegmentDurationSec : _config.Value.SegmentDurationSec;

            // 1. Presign
            var presignBody = new
            {
                device_id = _authManager.DeviceId,
                session_id = sessionId,
                sequence_num = sequenceNum,
                size_bytes = fileBytes.Length,
                duration_ms = segDuration * 1000,
                checksum_sha256 = checksum,
                content_type = "video/mp4",
                metadata = new { resolution, fps, codec = "h264" }
            };

            var presignUrl = $"{baseUrl}/api/ingest/v1/ingest/presign";
            var presignResponse = await _apiClient.PostAsync<PresignResponse>(presignUrl, presignBody, ct);
            if (presignResponse?.UploadUrl == null)
            {
                _logger.LogError("Presign failed for segment {Seq}", sequenceNum);
                return false;
            }

            // 2. PUT binary to S3
            var putResponse = await _apiClient.PutBinaryAsync(
                presignResponse.UploadUrl, fileBytes, "video/mp4", ct);
            if (!putResponse.IsSuccessStatusCode)
            {
                _logger.LogError("S3 upload failed: {Status}", putResponse.StatusCode);
                return false;
            }

            // 3. Confirm
            var confirmBody = new
            {
                segment_id = presignResponse.SegmentId,
                checksum_sha256 = checksum
            };
            var confirmUrl = $"{baseUrl}/api/ingest/v1/ingest/confirm";
            await _apiClient.PostAsync<object>(confirmUrl, confirmBody, ct);

            _logger.LogInformation("Segment {Seq} uploaded ({Size} bytes)", sequenceNum, fileBytes.Length);
            return true;
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Failed to upload segment {Seq}", sequenceNum);
            return false;
        }
    }
}

public class PresignResponse
{
    public string? SegmentId { get; set; }
    public string? UploadUrl { get; set; }
    public string? UploadMethod { get; set; }
    public int ExpiresInSec { get; set; }
}
