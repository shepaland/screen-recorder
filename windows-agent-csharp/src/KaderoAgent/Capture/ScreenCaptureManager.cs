using KaderoAgent.Configuration;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;

namespace KaderoAgent.Capture;

public class ScreenCaptureManager
{
    private readonly IOptions<AgentConfig> _config;
    private readonly ILogger<ScreenCaptureManager> _logger;
    private FfmpegProcess? _ffmpeg;
    private string _outputDir = "";
    private bool _isRecording;

    public bool IsRecording => _isRecording;
    public string OutputDirectory => _outputDir;

    public ScreenCaptureManager(IOptions<AgentConfig> config, ILogger<ScreenCaptureManager> logger)
    {
        _config = config;
        _logger = logger;
    }

    public void Start(string sessionId, int fps = 5, int segmentDuration = 10, string quality = "medium")
    {
        if (_isRecording) return;

        _outputDir = Path.Combine(_config.Value.DataPath, "segments", sessionId);
        Directory.CreateDirectory(_outputDir);

        var bitrate = quality switch
        {
            "low" => "300k",
            "medium" => "800k",
            "high" => "1500k",
            _ => "800k"
        };

        var outputPattern = Path.Combine(_outputDir, "segment_%05d.mp4");

        var ffmpegArgs = $"-f gdigrab -framerate {fps} -i desktop " +
            $"-c:v libx264 -preset ultrafast -tune zerolatency -pix_fmt yuv420p " +
            $"-b:v {bitrate} -g {fps * 2} " +
            $"-f segment -segment_time {segmentDuration} " +
            $"-segment_format mp4 -reset_timestamps 1 " +
            $"-movflags +frag_keyframe+empty_moov+default_base_moof " +
            $"-y \"{outputPattern}\"";

        _ffmpeg = new FfmpegProcess(_config.Value.FfmpegPath, _logger);
        _ffmpeg.Start(ffmpegArgs);
        _isRecording = true;

        _logger.LogInformation("Screen capture started: fps={Fps}, segment={Seg}s, quality={Q}",
            fps, segmentDuration, quality);
    }

    public void Stop()
    {
        if (!_isRecording || _ffmpeg == null) return;

        _ffmpeg.Stop();
        _isRecording = false;
        _logger.LogInformation("Screen capture stopped");
    }
}
