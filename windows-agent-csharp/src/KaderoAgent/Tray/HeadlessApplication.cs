namespace KaderoAgent.Tray;

using System.Windows.Forms;
using KaderoAgent.Ipc;
using Microsoft.Win32;

/// <summary>
/// Headless analytics collector — runs TrayWindowTracker + InputTracker + PipeClient
/// without any UI (no tray icon, no windows). Requires STA message pump for low-level hooks.
///
/// ARCHITECTURE: Same pipe I/O pattern as TrayApplication — all pipe calls on thread pool,
/// status polling via System.Threading.Timer. No UI marshaling needed.
/// </summary>
public class HeadlessApplication : ApplicationContext
{
    private readonly PipeClient _pipeClient;
    private readonly TrayWindowTracker _windowTracker;
    private readonly InputTracker _inputTracker;
    private System.Threading.Timer? _statusTimer;

    private string _lastConnectionStatus = "";
    private int _polling; // 0=idle, 1=busy — Interlocked for thread safety

    public HeadlessApplication()
    {
        _pipeClient    = new PipeClient();
        _windowTracker = new TrayWindowTracker();
        _inputTracker  = new InputTracker();

        // Status polling on thread pool — needed to get SegmentStartTs for video timecode binding
        _statusTimer = new System.Threading.Timer(
            callback: _ => PollStatusOnThreadPool(),
            state: null,
            dueTime: 0,
            period: 3000);

        // Start input tracking (low-level mouse/keyboard hooks — requires STA message pump)
        _inputTracker.Start();

        // Start active window tracking — attach InputTracker so events are sent via same pipe
        _windowTracker.SetInputTracker(_inputTracker);
        _windowTracker.Start();

        // Graceful shutdown on logoff/shutdown
        SystemEvents.SessionEnding += OnSessionEnding;
    }

    /// <summary>
    /// Runs on thread pool thread. Polls agent status to get SegmentStartTs
    /// for binding input events to video segments.
    /// </summary>
    private void PollStatusOnThreadPool()
    {
        if (Interlocked.CompareExchange(ref _polling, 1, 0) != 0)
            return;

        try
        {
            if (!_pipeClient.IsConnected)
            {
                // Allow more time for Service to start PipeServer after system reboot.
                var timeout = _lastConnectionStatus == "" ? 5000 : 1000;
                var connected = _pipeClient.ConnectAsync(timeout).GetAwaiter().GetResult();
                if (!connected)
                    return;
            }

            var status = _pipeClient.GetStatusAsync().GetAwaiter().GetResult();
            if (status != null)
            {
                _lastConnectionStatus = "connected";
                // Update InputTracker with segment context for video timecode binding
                _inputTracker.UpdateSegmentContext(status.SegmentStartTs);
            }
        }
        catch
        {
            _lastConnectionStatus = "disconnected";
        }
        finally
        {
            Interlocked.Exchange(ref _polling, 0);
        }
    }

    private void OnSessionEnding(object? sender, SessionEndingEventArgs e)
    {
        Shutdown();
    }

    private void Shutdown()
    {
        _statusTimer?.Dispose();
        _statusTimer = null;
        _windowTracker.Dispose();
        _inputTracker.Dispose();
        _pipeClient.Dispose();
        SystemEvents.SessionEnding -= OnSessionEnding;
        Application.Exit();
    }
}
