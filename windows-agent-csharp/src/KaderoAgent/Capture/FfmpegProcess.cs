using System.Diagnostics;
using Microsoft.Extensions.Logging;

namespace KaderoAgent.Capture;

public class FfmpegProcess
{
    private Process? _process;
    private readonly string _ffmpegPath;
    private readonly ILogger _logger;

    public FfmpegProcess(string ffmpegPath, ILogger logger)
    {
        _ffmpegPath = ffmpegPath;
        _logger = logger;
    }

    public void Start(string arguments)
    {
        _process = new Process
        {
            StartInfo = new ProcessStartInfo
            {
                FileName = _ffmpegPath,
                Arguments = arguments,
                UseShellExecute = false,
                RedirectStandardInput = true,
                RedirectStandardError = true,
                CreateNoWindow = true
            }
        };

        _process.ErrorDataReceived += (_, e) =>
        {
            if (!string.IsNullOrEmpty(e.Data))
                _logger.LogDebug("[ffmpeg] {Data}", e.Data);
        };

        _process.Start();
        _process.BeginErrorReadLine();
        _logger.LogInformation("FFmpeg started, PID={Pid}", _process.Id);
    }

    public void Stop()
    {
        if (_process == null || _process.HasExited) return;

        try
        {
            // Send 'q' to ffmpeg stdin for graceful stop
            _process.StandardInput.Write("q");
            _process.StandardInput.Flush();

            if (!_process.WaitForExit(10000))
            {
                _logger.LogWarning("FFmpeg did not exit gracefully, killing");
                _process.Kill();
            }
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error stopping FFmpeg");
            try { _process.Kill(); } catch { }
        }
    }
}
