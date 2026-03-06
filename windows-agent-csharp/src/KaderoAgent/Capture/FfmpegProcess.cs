using System.Diagnostics;
using Microsoft.Extensions.Logging;

namespace KaderoAgent.Capture;

public class FfmpegProcess
{
    private Process? _process;
    private int _interactivePid = -1;
    private readonly string _ffmpegPath;
    private readonly ILogger _logger;
    private readonly bool _isService;

    public FfmpegProcess(string ffmpegPath, ILogger logger)
    {
        _ffmpegPath = ffmpegPath;
        _logger = logger;
        // Detect if running as a Windows service (Session 0)
        _isService = !Environment.UserInteractive;
    }

    /// <summary>Start FFmpeg. Returns true if process was launched successfully.</summary>
    public bool Start(string arguments)
    {
        if (_isService)
        {
            return StartInUserSession(arguments);
        }
        else
        {
            return StartDirect(arguments);
        }
    }

    private bool StartInUserSession(string arguments)
    {
        _logger.LogInformation("Running as service, launching FFmpeg in user session");

        var pid = InteractiveProcessLauncher.LaunchInUserSession(
            _ffmpegPath, arguments, null, _logger);

        if (pid <= 0)
        {
            _logger.LogError("Failed to launch FFmpeg in user session");
            return false;
        }

        _interactivePid = pid;
        try
        {
            _process = Process.GetProcessById(pid);
            _logger.LogInformation("FFmpeg started in user session, PID={Pid}", pid);
            return true;
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "FFmpeg process PID={Pid} exited immediately", pid);
            _interactivePid = -1;
            return false;
        }
    }

    private bool StartDirect(string arguments)
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

        try
        {
            _process.Start();
            _process.BeginErrorReadLine();
            _logger.LogInformation("FFmpeg started directly, PID={Pid}", _process.Id);
            return true;
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Failed to start FFmpeg directly");
            _process = null;
            return false;
        }
    }

    public bool HasExited
    {
        get
        {
            try { return _process == null || _process.HasExited; }
            catch { return true; }
        }
    }

    public void Stop()
    {
        if (_process == null || _process.HasExited) return;

        try
        {
            if (_isService && _interactivePid > 0)
            {
                // For interactive session process, just kill it
                _logger.LogInformation("Killing FFmpeg PID={Pid}", _interactivePid);
                _process.Kill();
            }
            else
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
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error stopping FFmpeg");
            try { _process.Kill(); } catch { }
        }
    }
}
