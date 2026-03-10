namespace KaderoAgent.Tray;

using System.Diagnostics;
using System.Windows.Forms;
using KaderoAgent.Ipc;
using KaderoAgent.Resources;
using KaderoAgent.Util;

/// <summary>
/// Tray application — UI shell for monitoring and configuring KaderoAgent Windows Service.
/// Does NOT perform any recording or upload. Only communicates with Service via Named Pipe.
///
/// ARCHITECTURE: All pipe I/O runs on thread pool threads via System.Threading.Timer.
/// UI updates are marshaled to STA thread via BeginInvoke. This prevents
/// WindowsFormsSynchronizationContext deadlocks that freeze the context menu.
/// </summary>
public class TrayApplication : ApplicationContext
{
    private readonly NotifyIcon _trayIcon;
    private readonly PipeClient _pipeClient;
    private readonly TrayWindowTracker _windowTracker;
    private StatusWindow? _statusWindow;
    private AboutDialog? _aboutDialog;
    private System.Threading.Timer? _statusTimer;

    // Menu items that need updating
    private ToolStripMenuItem _statusItem = null!;
    private string _lastConnectionStatus = "";
    private string _lastRecordingStatus = "";
    private int _polling; // 0=idle, 1=busy — Interlocked for thread safety

    // Hidden control for BeginInvoke marshaling (NotifyIcon has no Invoke method)
    private readonly Control _invokeControl;

    public TrayApplication()
    {
        _pipeClient    = new PipeClient();
        _windowTracker = new TrayWindowTracker();

        // Create hidden control on STA thread for marshaling UI calls
        _invokeControl = new Control();
        _invokeControl.CreateControl();

        _trayIcon = new NotifyIcon
        {
            Icon = TrayIcons.Unknown,
            Text = "Кадеро Agent — Запуск...",
            Visible = true,
            ContextMenuStrip = CreateContextMenu()
        };

        _trayIcon.DoubleClick += OnTrayDoubleClick;

        // Start background status polling on thread pool
        StartStatusPolling();

        // Start active window tracking (runs in interactive session — GetForegroundWindow works here)
        _windowTracker.Start();
    }

    private ContextMenuStrip CreateContextMenu()
    {
        var menu = new ContextMenuStrip();

        var openItem = new ToolStripMenuItem("Открыть Кадеро");
        openItem.Font = new System.Drawing.Font(openItem.Font, System.Drawing.FontStyle.Bold);
        openItem.Click += OnTrayDoubleClick;
        menu.Items.Add(openItem);

        _statusItem = new ToolStripMenuItem("Статус: Запуск...") { Enabled = false };
        menu.Items.Add(_statusItem);

        menu.Items.Add(new ToolStripSeparator());

        var reconnectItem = new ToolStripMenuItem("Переподключиться");
        reconnectItem.Click += OnReconnect;
        menu.Items.Add(reconnectItem);

        var restartItem = new ToolStripMenuItem("Перезапустить сервис");
        restartItem.Click += OnRestartService;
        menu.Items.Add(restartItem);

        menu.Items.Add(new ToolStripSeparator());

        var autoStartItem = new ToolStripMenuItem("Запускать при входе в систему")
        {
            Checked = AutoStartHelper.IsAutoStartEnabled(),
            CheckOnClick = true
        };
        autoStartItem.CheckedChanged += (_, _) =>
        {
            if (autoStartItem.Checked)
                AutoStartHelper.EnableAutoStart();
            else
                AutoStartHelper.DisableAutoStart();
        };
        menu.Items.Add(autoStartItem);

        var aboutItem = new ToolStripMenuItem("О программе");
        aboutItem.Click += OnAbout;
        menu.Items.Add(aboutItem);

        var exitItem = new ToolStripMenuItem("Выход");
        exitItem.Click += OnExit;
        menu.Items.Add(exitItem);

        return menu;
    }

    private void StartStatusPolling()
    {
        // System.Threading.Timer fires on THREAD POOL, not on UI/STA thread.
        // Pipe I/O never touches the STA message pump.
        _statusTimer = new System.Threading.Timer(
            callback: _ => PollStatusOnThreadPool(),
            state: null,
            dueTime: 0,
            period: 3000);
    }

    /// <summary>
    /// Runs on thread pool thread. All pipe I/O completes here.
    /// UI updates are posted to STA thread via BeginInvoke (non-blocking).
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
                // First attempt uses 5s timeout; subsequent reconnects use 1s.
                var timeout = _lastConnectionStatus == "" ? 5000 : 1000;
                var connected = _pipeClient.ConnectAsync(timeout).GetAwaiter().GetResult();
                if (!connected)
                {
                    MarshalUpdateTrayIcon("disconnected", "stopped");
                    return;
                }
            }

            var status = _pipeClient.GetStatusAsync().GetAwaiter().GetResult();
            if (status != null)
            {
                // Use AgentStateName for richer tray icon states; fallback to RecordingStatus
                var effectiveState = !string.IsNullOrEmpty(status.AgentStateName)
                    ? status.AgentStateName
                    : status.RecordingStatus;
                MarshalUpdateTrayIcon(status.ConnectionStatus, effectiveState);
            }
            else
                MarshalUpdateTrayIcon("disconnected", "stopped");
        }
        catch
        {
            MarshalUpdateTrayIcon("disconnected", "stopped");
        }
        finally
        {
            Interlocked.Exchange(ref _polling, 0);
        }
    }

    /// <summary>
    /// Posts UI update to STA thread. Non-blocking — returns immediately.
    /// </summary>
    private void MarshalUpdateTrayIcon(string connectionStatus, string recordingStatus)
    {
        if (_invokeControl.IsDisposed) return;
        try
        {
            _invokeControl.BeginInvoke(() => UpdateTrayIcon(connectionStatus, recordingStatus));
        }
        catch (ObjectDisposedException) { }
        catch (InvalidOperationException) { }
    }

    /// <summary>
    /// Update tray icon and tooltip based on connection status and agent state.
    /// The agentState parameter can be: "starting", "configuring", "awaiting_user",
    /// "online", "recording", "idle", "error", "stopped", or legacy "stopped"/"recording".
    /// </summary>
    private void UpdateTrayIcon(string connectionStatus, string agentState)
    {
        if (connectionStatus == _lastConnectionStatus && agentState == _lastRecordingStatus)
            return;
        _lastConnectionStatus = connectionStatus;
        _lastRecordingStatus = agentState;

        Icon newIcon;
        string tooltip;
        string statusText;

        if (connectionStatus != "connected" && connectionStatus != "")
        {
            // Disconnected/error states take priority
            if (connectionStatus == "reconnecting")
            {
                newIcon = TrayIcons.Reconnecting;
                tooltip = "Кадеро Agent — Переподключение...";
                statusText = "Статус: Переподключение";
            }
            else if (connectionStatus == "error")
            {
                newIcon = TrayIcons.Disconnected;
                tooltip = "Кадеро Agent — Ошибка подключения";
                statusText = "Статус: Ошибка";
            }
            else
            {
                newIcon = TrayIcons.Unknown;
                tooltip = "Кадеро Agent — Нет связи с сервисом";
                statusText = "Статус: Нет связи";
            }
        }
        else
        {
            // Connected: use agent state for icon/text
            switch (agentState)
            {
                case "recording":
                    newIcon = TrayIcons.Recording;
                    tooltip = "Кадеро Agent — Запись";
                    statusText = "Статус: Запись";
                    break;
                case "online":
                    newIcon = TrayIcons.Idle;
                    tooltip = "Кадеро Agent — Онлайн";
                    statusText = "Статус: Онлайн";
                    break;
                case "recording_disabled":
                    newIcon = TrayIcons.Idle;
                    tooltip = "Кадеро Agent — Запись отключена";
                    statusText = "Статус: Запись отключена";
                    break;
                case "idle":
                    newIcon = TrayIcons.Idle;
                    tooltip = "Кадеро Agent — Неактивен";
                    statusText = "Статус: Неактивен";
                    break;
                case "awaiting_user":
                    newIcon = TrayIcons.Idle;
                    tooltip = "Кадеро Agent — Ожидание входа";
                    statusText = "Статус: Ожидание входа";
                    break;
                case "configuring":
                case "starting":
                    newIcon = TrayIcons.Reconnecting;
                    tooltip = "Кадеро Agent — Запуск...";
                    statusText = "Статус: Запуск";
                    break;
                case "error":
                    newIcon = TrayIcons.Disconnected;
                    tooltip = "Кадеро Agent — Ошибка";
                    statusText = "Статус: Ошибка";
                    break;
                case "stopped":
                    newIcon = TrayIcons.Unknown;
                    tooltip = "Кадеро Agent — Остановлен";
                    statusText = "Статус: Остановлен";
                    break;
                default:
                    newIcon = TrayIcons.Idle;
                    tooltip = "Кадеро Agent — Подключен";
                    statusText = "Статус: Подключен";
                    break;
            }
        }

        _trayIcon.Icon = newIcon;
        _trayIcon.Text = tooltip;
        _statusItem.Text = statusText;
    }

    private void OnTrayDoubleClick(object? sender, EventArgs e)
    {
        if (_statusWindow == null || _statusWindow.IsDisposed)
        {
            _statusWindow = new StatusWindow(_pipeClient, () => { });
        }

        _statusWindow.Show();
        _statusWindow.BringToFront();
        _statusWindow.Activate();
    }

    private void OnReconnect(object? sender, EventArgs e)
    {
        // Pipe I/O on thread pool — never on STA thread
        ThreadPool.QueueUserWorkItem(_ =>
        {
            try
            {
                if (!_pipeClient.IsConnected)
                    _pipeClient.ConnectAsync().GetAwaiter().GetResult();
                _pipeClient.ReconnectAsync(null, null).GetAwaiter().GetResult();
            }
            catch { }
        });
    }

    private void OnRestartService(object? sender, EventArgs e)
    {
        try
        {
            var psi = new ProcessStartInfo
            {
                FileName = "cmd.exe",
                Arguments = "/c sc stop KaderoAgent && timeout /t 3 && sc start KaderoAgent",
                Verb = "runas",
                UseShellExecute = true,
                WindowStyle = ProcessWindowStyle.Hidden
            };
            Process.Start(psi);
        }
        catch (Exception ex)
        {
            MessageBox.Show($"Не удалось перезапустить сервис:\n{ex.Message}",
                "Ошибка", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
    }

    private void OnAbout(object? sender, EventArgs e)
    {
        if (_aboutDialog == null || _aboutDialog.IsDisposed)
            _aboutDialog = new AboutDialog();
        _aboutDialog.ShowDialog();
    }

    private void OnExit(object? sender, EventArgs e)
    {
        _statusTimer?.Dispose();
        _windowTracker.Dispose();
        _statusWindow?.Close();
        _aboutDialog?.Close();
        _pipeClient.Dispose();
        _trayIcon.Visible = false;
        _invokeControl.Dispose();
        Application.Exit();
    }
}
