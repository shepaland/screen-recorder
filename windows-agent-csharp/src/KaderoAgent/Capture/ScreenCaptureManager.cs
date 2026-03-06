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
    private int _currentFps;
    private string _currentResolution = "";

    public bool IsRecording => _isRecording;
    public string OutputDirectory => _outputDir;
    public int CurrentFps => _currentFps;
    public string CurrentResolution => _currentResolution;

    /// <summary>True if FFmpeg process has exited unexpectedly while recording flag is still set.</summary>
    public bool HasCrashed => _isRecording && _ffmpeg != null && _ffmpeg.HasExited;

    public ScreenCaptureManager(IOptions<AgentConfig> config, ILogger<ScreenCaptureManager> logger)
    {
        _config = config;
        _logger = logger;
    }

    /// <summary>Start screen capture. Returns true if FFmpeg was launched successfully.</summary>
    public bool Start(string sessionId, int fps = 1, int segmentDuration = 10,
        string quality = "medium", string resolution = "1280x720")
    {
        if (_isRecording) return true; // Already recording

        _outputDir = Path.Combine(_config.Value.DataPath, "segments", sessionId);
        Directory.CreateDirectory(_outputDir);

        _currentFps = fps;
        _currentResolution = resolution;

        var bitrate = quality switch
        {
            "low" => "300k",
            "medium" => "800k",
            "high" => "1500k",
            _ => "800k"
        };

        var outputPattern = Path.Combine(_outputDir, "segment_%05d.mp4");

        // Build FFmpeg args with resolution scaling
        var scaleFilter = "";
        if (!string.IsNullOrEmpty(resolution) && resolution.Contains('x'))
        {
            var parts = resolution.Split('x');
            if (parts.Length == 2 && int.TryParse(parts[0], out var w) && int.TryParse(parts[1], out var h))
            {
                scaleFilter = $"-vf scale={w}:{h} ";
            }
        }

        var ffmpegArgs = $"-f gdigrab -framerate {fps} -i desktop " +
            $"{scaleFilter}" +
            $"-c:v libx264 -preset ultrafast -tune zerolatency -pix_fmt yuv420p " +
            $"-b:v {bitrate} -g {Math.Max(fps * 2, 2)} " +
            $"-f segment -segment_time {segmentDuration} " +
            $"-segment_format mp4 -reset_timestamps 1 " +
            $"-movflags +frag_keyframe+empty_moov+default_base_moof " +
            $"-y \"{outputPattern}\"";

        _ffmpeg = new FfmpegProcess(_config.Value.FfmpegPath, _logger);
        var started = _ffmpeg.Start(ffmpegArgs);

        if (!started)
        {
            _logger.LogError("FFmpeg failed to start, not marking as recording");
            _ffmpeg = null;
            return false;
        }

        _isRecording = true;
        _logger.LogInformation(
            "Screen capture started: fps={Fps}, segment={Seg}s, quality={Q}, resolution={Res}",
            fps, segmentDuration, quality, resolution);
        return true;
    }

    public void Stop()
    {
        if (!_isRecording || _ffmpeg == null) return;

        _ffmpeg.Stop();
        _isRecording = false;
        _logger.LogInformation("Screen capture stopped");
    }

    /// <summary>Force-reset state after crash detection (without trying to stop FFmpeg).</summary>
    public void ResetAfterCrash()
    {
        _isRecording = false;
        _ffmpeg = null;
        _logger.LogWarning("Screen capture state reset after crash");
    }
}
