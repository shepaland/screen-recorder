namespace KaderoAgent.Tray;

using System.Diagnostics;
using System.Windows.Forms;
using KaderoAgent.Ipc;

/// <summary>
/// Tray application — UI shell for monitoring and configuring KaderoAgent Windows Service.
/// Does NOT perform any recording or upload. Only communicates with Service via Named Pipe.
/// </summary>
public class TrayApplication : ApplicationContext
{
    private readonly NotifyIcon _trayIcon;
    private readonly PipeClient _pipeClient;
    private StatusWindow? _statusWindow;
    private AboutDialog? _aboutDialog;
    private System.Windows.Forms.Timer _statusTimer = null!;

    // Menu items that need updating
    private ToolStripMenuItem _statusItem = null!;

    public TrayApplication()
    {
        _pipeClient = new PipeClient();

        _trayIcon = new NotifyIcon
        {
            Icon = TrayIcons.Unknown,
            Text = "Кадеро Agent — Запуск...",
            Visible = true,
            ContextMenuStrip = CreateContextMenu()
        };

        _trayIcon.DoubleClick += OnTrayDoubleClick;

        // Start background status polling
        StartStatusPolling();
    }

    private ContextMenuStrip CreateContextMenu()
    {
        var menu = new ContextMenuStrip();

        var openItem = new ToolStripMenuItem("Открыть Кадеро");
        openItem.Font = new System.Drawing.Font(openItem.Font, System.Drawing.FontStyle.Bold); // Bold = default action
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
        _statusTimer = new System.Windows.Forms.Timer { Interval = 3000 };
        _statusTimer.Tick += async (_, _) => await PollStatusAsync();
        _statusTimer.Start();
        _ = PollStatusAsync(); // Initial
    }

    private async Task PollStatusAsync()
    {
        try
        {
            if (!_pipeClient.IsConnected)
            {
                var connected = await _pipeClient.ConnectAsync(1000);
                if (!connected)
                {
                    UpdateTrayIcon("disconnected", "stopped");
                    return;
                }
            }

            var status = await _pipeClient.GetStatusAsync();
            if (status != null)
            {
                UpdateTrayIcon(status.ConnectionStatus, status.RecordingStatus);
            }
            else
            {
                UpdateTrayIcon("disconnected", "stopped");
            }
        }
        catch
        {
            UpdateTrayIcon("disconnected", "stopped");
        }
    }

    private void UpdateTrayIcon(string connectionStatus, string recordingStatus)
    {
        Icon newIcon;
        string tooltip;
        string statusText;

        if (connectionStatus == "connected" && recordingStatus == "recording")
        {
            newIcon = TrayIcons.Recording;
            tooltip = "Кадеро Agent — Запись";
            statusText = "Статус: Запись";
        }
        else if (connectionStatus == "connected")
        {
            newIcon = TrayIcons.Idle;
            tooltip = "Кадеро Agent — Подключен";
            statusText = "Статус: Подключен";
        }
        else if (connectionStatus == "reconnecting")
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

        _trayIcon.Icon = newIcon;
        _trayIcon.Text = tooltip;
        _statusItem.Text = statusText;
    }

    private void OnTrayDoubleClick(object? sender, EventArgs e)
    {
        if (_statusWindow == null || _statusWindow.IsDisposed)
        {
            _statusWindow = new StatusWindow(_pipeClient, () => { /* callback after reconnect */ });
        }

        _statusWindow.Show();
        _statusWindow.BringToFront();
        _statusWindow.Activate();
    }

    private async void OnReconnect(object? sender, EventArgs e)
    {
        try
        {
            if (!_pipeClient.IsConnected)
                await _pipeClient.ConnectAsync();

            await _pipeClient.ReconnectAsync(null, null);
        }
        catch { /* silently ignore */ }
    }

    private void OnRestartService(object? sender, EventArgs e)
    {
        try
        {
            // Run sc.exe as admin to restart service
            var psi = new ProcessStartInfo
            {
                FileName = "cmd.exe",
                Arguments = "/c sc stop KaderoAgent && timeout /t 3 && sc start KaderoAgent",
                Verb = "runas", // UAC elevation
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
        _statusTimer?.Stop();
        _statusWindow?.Close();
        _aboutDialog?.Close();
        _pipeClient.Dispose();
        _trayIcon.Visible = false;
        Application.Exit();
    }
}
