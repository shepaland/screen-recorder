namespace KaderoAgent.Tray;

using System.Windows.Forms;
using System.Drawing;
using KaderoAgent.Ipc;

/// <summary>
/// Status window shown on double-click of tray icon.
/// Shows connection status, recording status, parameters, and allows editing server URL/token.
/// </summary>
public class StatusWindow : Form
{
    private readonly PipeClient _pipeClient;
    private readonly Action _onReconnect;

    // Status indicators
    private Label _connectionStatusLabel = null!;
    private Panel _connectionIndicator = null!;
    private Label _recordingStatusLabel = null!;
    private Panel _recordingIndicator = null!;

    // Read-only parameters
    private Label _fpsValue = null!;
    private Label _qualityValue = null!;
    private Label _segmentValue = null!;
    private Label _heartbeatValue = null!;
    private Label _deviceIdValue = null!;

    // Metrics
    private Label _cpuValue = null!;
    private Label _memoryValue = null!;
    private Label _diskValue = null!;
    private Label _queueValue = null!;
    private Label _lastHeartbeatValue = null!;

    // Editable fields
    private TextBox _serverUrlBox = null!;
    private TextBox _tokenBox = null!;
    private TextBox _usernameBox = null!;
    private TextBox _passwordBox = null!;
    private Button _reconnectBtn = null!;
    private Label _statusMessage = null!;

    // Auto-refresh timer
    private System.Windows.Forms.Timer _refreshTimer = null!;

    public StatusWindow(PipeClient pipeClient, Action onReconnect)
    {
        _pipeClient = pipeClient;
        _onReconnect = onReconnect;
        InitializeComponent();
        StartAutoRefresh();
    }

    private void InitializeComponent()
    {
        Text = "Кадеро — Статус агента";
        Size = new Size(520, 700);
        StartPosition = FormStartPosition.CenterScreen;
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        MinimizeBox = true;
        ShowInTaskbar = true;
        Icon = SystemIcons.Application;

        var y = 15;
        var leftCol = 20;
        var rightCol = 180;
        var width = 300;

        // === Connection Status ===
        AddSectionHeader("Подключение", ref y);
        _connectionIndicator = AddIndicator(leftCol, y);
        _connectionStatusLabel = AddLabel("Неизвестно", rightCol - 40, y, width);
        y += 30;

        // === Recording Status ===
        _recordingIndicator = AddIndicator(leftCol, y);
        _recordingStatusLabel = AddLabel("Неизвестно", rightCol - 40, y, width);
        y += 35;

        // === Device ID ===
        AddLabel("ID устройства:", leftCol, y, 150, bold: true);
        _deviceIdValue = AddLabel("—", rightCol, y, width);
        y += 25;

        // === Parameters Section ===
        AddSectionHeader("Параметры записи", ref y);

        AddLabel("FPS:", leftCol, y, 150, bold: true);
        _fpsValue = AddLabel("—", rightCol, y, width);
        y += 22;

        AddLabel("Качество:", leftCol, y, 150, bold: true);
        _qualityValue = AddLabel("—", rightCol, y, width);
        y += 22;

        AddLabel("Длит. сегмента:", leftCol, y, 150, bold: true);
        _segmentValue = AddLabel("—", rightCol, y, width);
        y += 22;

        AddLabel("Heartbeat:", leftCol, y, 150, bold: true);
        _heartbeatValue = AddLabel("—", rightCol, y, width);
        y += 30;

        // === Metrics Section ===
        AddSectionHeader("Метрики", ref y);

        AddLabel("CPU:", leftCol, y, 80, bold: true);
        _cpuValue = AddLabel("—", leftCol + 80, y, 80);
        AddLabel("RAM:", leftCol + 170, y, 80, bold: true);
        _memoryValue = AddLabel("—", leftCol + 240, y, 80);
        y += 22;

        AddLabel("Диск:", leftCol, y, 80, bold: true);
        _diskValue = AddLabel("—", leftCol + 80, y, 80);
        AddLabel("Очередь:", leftCol + 170, y, 80, bold: true);
        _queueValue = AddLabel("—", leftCol + 240, y, 80);
        y += 22;

        AddLabel("Посл. heartbeat:", leftCol, y, 150, bold: true);
        _lastHeartbeatValue = AddLabel("—", rightCol, y, width);
        y += 35;

        // === Settings Section ===
        AddSectionHeader("Настройки подключения", ref y);

        AddLabel("Адрес сервера:", leftCol, y, 150, bold: true);
        y += 22;
        _serverUrlBox = new TextBox { Location = new Point(leftCol, y), Size = new Size(460, 25) };
        Controls.Add(_serverUrlBox);
        y += 32;

        AddLabel("Токен регистрации:", leftCol, y, 200, bold: true);
        y += 22;
        _tokenBox = new TextBox { Location = new Point(leftCol, y), Size = new Size(460, 25), PlaceholderText = "drt_... (оставьте пустым для текущего)" };
        Controls.Add(_tokenBox);
        y += 32;

        AddLabel("Имя пользователя:", leftCol, y, 200, bold: true);
        _usernameBox = new TextBox { Location = new Point(rightCol, y - 2), Size = new Size(300, 25) };
        Controls.Add(_usernameBox);
        y += 28;

        AddLabel("Пароль:", leftCol, y, 200, bold: true);
        _passwordBox = new TextBox { Location = new Point(rightCol, y - 2), Size = new Size(300, 25), UseSystemPasswordChar = true };
        Controls.Add(_passwordBox);
        y += 35;

        _reconnectBtn = new Button
        {
            Text = "Переподключиться",
            Location = new Point(leftCol, y),
            Size = new Size(160, 35),
            BackColor = Color.FromArgb(183, 28, 28),
            ForeColor = Color.White,
            FlatStyle = FlatStyle.Flat,
            Cursor = Cursors.Hand
        };
        _reconnectBtn.FlatAppearance.BorderSize = 0;
        _reconnectBtn.Click += OnReconnect;
        Controls.Add(_reconnectBtn);

        _statusMessage = new Label
        {
            Location = new Point(leftCol + 170, y + 8),
            Size = new Size(300, 25),
            ForeColor = Color.Gray,
            Text = ""
        };
        Controls.Add(_statusMessage);
    }

    private void AddSectionHeader(string text, ref int y)
    {
        var label = new Label
        {
            Text = text,
            Location = new Point(20, y),
            AutoSize = true,
            Font = new Font(Font.FontFamily, 10, FontStyle.Bold),
            ForeColor = Color.FromArgb(183, 28, 28) // Alfa-Bank red
        };
        Controls.Add(label);
        y += 25;
    }

    private Panel AddIndicator(int x, int y)
    {
        var panel = new Panel
        {
            Location = new Point(x, y + 2),
            Size = new Size(12, 12),
            BackColor = Color.Gray
        };
        panel.Paint += (_, e) =>
        {
            e.Graphics.SmoothingMode = System.Drawing.Drawing2D.SmoothingMode.AntiAlias;
            using var brush = new SolidBrush(panel.BackColor);
            e.Graphics.FillEllipse(brush, 0, 0, 11, 11);
        };
        Controls.Add(panel);
        return panel;
    }

    private Label AddLabel(string text, int x, int y, int width, bool bold = false)
    {
        var label = new Label
        {
            Text = text,
            Location = new Point(x, y),
            Size = new Size(width, 20),
            Font = bold ? new Font(Font.FontFamily, Font.Size, FontStyle.Bold) : Font
        };
        Controls.Add(label);
        return label;
    }

    private void StartAutoRefresh()
    {
        _refreshTimer = new System.Windows.Forms.Timer { Interval = 2000 }; // Every 2 sec
        _refreshTimer.Tick += async (_, _) => await RefreshStatusAsync();
        _refreshTimer.Start();
        _ = RefreshStatusAsync(); // Initial refresh
    }

    private async Task RefreshStatusAsync()
    {
        try
        {
            if (!_pipeClient.IsConnected)
            {
                var connected = await _pipeClient.ConnectAsync(1000);
                if (!connected)
                {
                    SetDisconnectedState();
                    return;
                }
            }

            var status = await _pipeClient.GetStatusAsync();
            if (status == null)
            {
                SetDisconnectedState();
                return;
            }

            UpdateUI(status);
        }
        catch
        {
            SetDisconnectedState();
        }
    }

    private void SetDisconnectedState()
    {
        if (InvokeRequired) { Invoke(SetDisconnectedState); return; }

        _connectionStatusLabel.Text = "Нет связи с сервисом";
        _connectionIndicator.BackColor = Color.Gray;
        _connectionIndicator.Invalidate();
        _recordingStatusLabel.Text = "Неизвестно";
        _recordingIndicator.BackColor = Color.Gray;
        _recordingIndicator.Invalidate();
    }

    private void UpdateUI(AgentStatus status)
    {
        if (InvokeRequired) { Invoke(() => UpdateUI(status)); return; }

        // Connection status
        switch (status.ConnectionStatus)
        {
            case "connected":
                _connectionStatusLabel.Text = "Подключен";
                _connectionIndicator.BackColor = Color.FromArgb(76, 175, 80);
                break;
            case "reconnecting":
                _connectionStatusLabel.Text = "Переподключение...";
                _connectionIndicator.BackColor = Color.FromArgb(255, 152, 0);
                break;
            case "error":
                _connectionStatusLabel.Text = $"Ошибка: {status.LastError ?? "неизвестная"}";
                _connectionIndicator.BackColor = Color.FromArgb(244, 67, 54);
                break;
            default:
                _connectionStatusLabel.Text = "Отключен";
                _connectionIndicator.BackColor = Color.FromArgb(158, 158, 158);
                break;
        }
        _connectionIndicator.Invalidate();

        // Recording status
        switch (status.RecordingStatus)
        {
            case "recording":
                _recordingStatusLabel.Text = "Запись идёт";
                _recordingIndicator.BackColor = Color.FromArgb(76, 175, 80);
                break;
            case "starting":
                _recordingStatusLabel.Text = "Запуск записи...";
                _recordingIndicator.BackColor = Color.FromArgb(255, 193, 7);
                break;
            default:
                _recordingStatusLabel.Text = "Остановлена";
                _recordingIndicator.BackColor = Color.FromArgb(158, 158, 158);
                break;
        }
        _recordingIndicator.Invalidate();

        // Device ID
        _deviceIdValue.Text = status.DeviceId ?? "—";

        // Parameters
        _fpsValue.Text = status.CaptureFps > 0 ? $"{status.CaptureFps} кадров/сек" : "—";
        _qualityValue.Text = status.Quality switch
        {
            "low" => "Низкое",
            "medium" => "Среднее",
            "high" => "Высокое",
            _ => status.Quality ?? "—"
        };
        _segmentValue.Text = status.SegmentDurationSec > 0 ? $"{status.SegmentDurationSec} сек" : "—";
        _heartbeatValue.Text = status.HeartbeatIntervalSec > 0 ? $"{status.HeartbeatIntervalSec} сек" : "—";

        // Metrics
        _cpuValue.Text = $"{status.CpuPercent:F1}%";
        _memoryValue.Text = $"{status.MemoryMb:F0} МБ";
        _diskValue.Text = $"{status.DiskFreeGb:F1} ГБ";
        _queueValue.Text = $"{status.SegmentsQueued}";
        _lastHeartbeatValue.Text = status.LastHeartbeatTs?.ToLocalTime().ToString("dd.MM.yyyy HH:mm:ss") ?? "—";

        // Fill server URL if empty
        if (string.IsNullOrEmpty(_serverUrlBox.Text) && !string.IsNullOrEmpty(status.ServerUrl))
        {
            _serverUrlBox.Text = status.ServerUrl;
        }
    }

    private async void OnReconnect(object? sender, EventArgs e)
    {
        _reconnectBtn.Enabled = false;
        _statusMessage.Text = "Переподключение...";
        _statusMessage.ForeColor = Color.Gray;

        try
        {
            if (!_pipeClient.IsConnected)
                await _pipeClient.ConnectAsync();

            var response = await _pipeClient.ReconnectAsync(
                _serverUrlBox.Text.Trim(),
                string.IsNullOrWhiteSpace(_tokenBox.Text) ? null : _tokenBox.Text.Trim(),
                string.IsNullOrWhiteSpace(_usernameBox.Text) ? null : _usernameBox.Text.Trim(),
                string.IsNullOrWhiteSpace(_passwordBox.Text) ? null : _passwordBox.Text
            );

            if (response?.Success == true)
            {
                _statusMessage.Text = "Подключено!";
                _statusMessage.ForeColor = Color.Green;
                _tokenBox.Text = ""; // Clear token after successful reconnect
                _onReconnect?.Invoke();
            }
            else
            {
                _statusMessage.Text = response?.Error ?? "Ошибка подключения";
                _statusMessage.ForeColor = Color.Red;
            }
        }
        catch (Exception ex)
        {
            _statusMessage.Text = $"Ошибка: {ex.Message}";
            _statusMessage.ForeColor = Color.Red;
        }
        finally
        {
            _reconnectBtn.Enabled = true;
        }
    }

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        // Hide instead of closing (tray keeps running)
        if (e.CloseReason == CloseReason.UserClosing)
        {
            e.Cancel = true;
            Hide();
            return;
        }
        _refreshTimer?.Stop();
        base.OnFormClosing(e);
    }
}
