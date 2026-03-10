namespace KaderoAgent.Tray;

using System.Drawing;
using System.Drawing.Drawing2D;
using System.Management;
using System.Windows.Forms;
using KaderoAgent.Ipc;
using KaderoAgent.Resources;

/// <summary>
/// Status window with glass/acrylic UI style.
/// Borderless, draggable, opens at bottom-right corner above taskbar.
///
/// ARCHITECTURE: All pipe I/O runs on thread pool threads via System.Threading.Timer.
/// UI updates are marshaled to STA thread via BeginInvoke.
/// </summary>
public class StatusWindow : Form
{
    private readonly PipeClient _pipeClient;
    private readonly Action _onReconnect;

    // Service status
    private Label _serviceStatusLabel = null!;
    private Panel _serviceIndicator = null!;
    private Panel _progressPanel = null!;
    private int _progressOffset;
    private System.Windows.Forms.Timer? _progressAnimTimer;

    // Status indicators
    private Label _connectionStatusLabel = null!;
    private Panel _connectionIndicator = null!;
    private Label _recordingStatusLabel = null!;
    private Panel _recordingIndicator = null!;

    // Read-only parameters
    private Label _fpsValue = null!;
    private Label _qualityValue = null!;
    private Label _resolutionValue = null!;
    private Label _segmentValue = null!;
    private Label _heartbeatValue = null!;
    private Label _autoStartValue = null!;
    private Label _maxSessionValue = null!;
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
    private Button _reconnectBtn = null!;
    private Label _statusMessage = null!;

    // Auto-refresh timer (thread pool based)
    private System.Threading.Timer? _refreshTimer;
    private int _refreshing; // 0=idle, 1=busy — Interlocked for thread safety

    public StatusWindow(PipeClient pipeClient, Action onReconnect)
    {
        _pipeClient = pipeClient;
        _onReconnect = onReconnect;
        SetStyle(ControlStyles.SupportsTransparentBackColor | ControlStyles.OptimizedDoubleBuffer, true);
        InitializeComponent();
        StartAutoRefresh();
    }

    private void InitializeComponent()
    {
        Text = "Кадеро — Статус агента";
        Size = new Size(480, 720);
        ShowInTaskbar = true;
        MaximizeBox = false;
        MinimizeBox = false;
        Icon = LogoHelper.CreateAppIcon(32);

        // Glass effect
        GlassHelper.ApplyGlassEffect(this);
        GlassHelper.PositionBottomRight(this);

        // Paint border
        Paint += (_, e) => GlassHelper.PaintGlassBorder(this, e);

        // ── Title bar ──
        var titleBar = GlassHelper.CreateTitleBar(this, "Кадеро", Width);
        Controls.Add(titleBar);

        // Enable drag on form background too
        GlassHelper.EnableDrag(this);

        var y = 48;
        var left = 20;
        var valX = 170;
        var fullWidth = Width - 40;

        // ── Connection Status ──
        Controls.Add(GlassHelper.CreateSectionHeader("Подключение", left, y));
        y += 28;

        _connectionIndicator = GlassHelper.CreateIndicator(left, y, GlassHelper.StatusGray);
        Controls.Add(_connectionIndicator);
        _connectionStatusLabel = GlassHelper.CreateLabel("Неизвестно", left + 22, y, 300);
        Controls.Add(_connectionStatusLabel);
        y += 24;

        _recordingIndicator = GlassHelper.CreateIndicator(left, y, GlassHelper.StatusGray);
        Controls.Add(_recordingIndicator);
        _recordingStatusLabel = GlassHelper.CreateLabel("Неизвестно", left + 22, y, 300);
        Controls.Add(_recordingStatusLabel);
        y += 24;

        // Service status
        _serviceIndicator = GlassHelper.CreateIndicator(left, y, GlassHelper.StatusGray);
        Controls.Add(_serviceIndicator);
        _serviceStatusLabel = GlassHelper.CreateLabel("Служба: неизвестно", left + 22, y, 300);
        Controls.Add(_serviceStatusLabel);
        y += 22;

        // Progress bar (hidden by default, shown during service start/stop)
        _progressPanel = new Panel
        {
            Location = new Point(left, y),
            Size = new Size(fullWidth, 3),
            BackColor = Color.Transparent,
            Visible = false
        };
        _progressPanel.Paint += PaintProgressBar;
        Controls.Add(_progressPanel);
        y += 10;

        // Separator
        Controls.Add(GlassHelper.CreateSeparator(left, y, fullWidth));
        y += 12;

        // ── Device ID ──
        Controls.Add(GlassHelper.CreateLabel("ID устройства", left, y, 140, bold: true, secondary: true));
        _deviceIdValue = GlassHelper.CreateLabel("—", valX, y, 280);
        Controls.Add(_deviceIdValue);
        y += 28;

        // ── Parameters Section ──
        Controls.Add(GlassHelper.CreateSectionHeader("Параметры записи", left, y));
        y += 28;

        Controls.Add(GlassHelper.CreateLabel("FPS", left, y, 140, secondary: true));
        _fpsValue = GlassHelper.CreateLabel("—", valX, y, 200);
        Controls.Add(_fpsValue);
        y += 22;

        Controls.Add(GlassHelper.CreateLabel("Качество", left, y, 140, secondary: true));
        _qualityValue = GlassHelper.CreateLabel("—", valX, y, 200);
        Controls.Add(_qualityValue);
        y += 22;

        Controls.Add(GlassHelper.CreateLabel("Разрешение", left, y, 140, secondary: true));
        _resolutionValue = GlassHelper.CreateLabel("—", valX, y, 200);
        Controls.Add(_resolutionValue);
        y += 22;

        Controls.Add(GlassHelper.CreateLabel("Сегмент", left, y, 140, secondary: true));
        _segmentValue = GlassHelper.CreateLabel("—", valX, y, 200);
        Controls.Add(_segmentValue);
        y += 22;

        Controls.Add(GlassHelper.CreateLabel("Heartbeat", left, y, 140, secondary: true));
        _heartbeatValue = GlassHelper.CreateLabel("—", valX, y, 200);
        Controls.Add(_heartbeatValue);
        y += 22;

        Controls.Add(GlassHelper.CreateLabel("Автостарт", left, y, 140, secondary: true));
        _autoStartValue = GlassHelper.CreateLabel("—", valX, y, 200);
        Controls.Add(_autoStartValue);
        y += 22;

        Controls.Add(GlassHelper.CreateLabel("Макс. сессия", left, y, 140, secondary: true));
        _maxSessionValue = GlassHelper.CreateLabel("—", valX, y, 200);
        Controls.Add(_maxSessionValue);
        y += 28;

        // Separator
        Controls.Add(GlassHelper.CreateSeparator(left, y, fullWidth));
        y += 12;

        // ── Metrics Section ──
        Controls.Add(GlassHelper.CreateSectionHeader("Метрики", left, y));
        y += 28;

        Controls.Add(GlassHelper.CreateLabel("CPU", left, y, 60, secondary: true));
        _cpuValue = GlassHelper.CreateLabel("—", left + 60, y, 70);
        Controls.Add(_cpuValue);
        Controls.Add(GlassHelper.CreateLabel("RAM", left + 140, y, 60, secondary: true));
        _memoryValue = GlassHelper.CreateLabel("—", left + 200, y, 80);
        Controls.Add(_memoryValue);
        y += 22;

        Controls.Add(GlassHelper.CreateLabel("Диск", left, y, 60, secondary: true));
        _diskValue = GlassHelper.CreateLabel("—", left + 60, y, 70);
        Controls.Add(_diskValue);
        Controls.Add(GlassHelper.CreateLabel("Очередь", left + 140, y, 70, secondary: true));
        _queueValue = GlassHelper.CreateLabel("—", left + 210, y, 70);
        Controls.Add(_queueValue);
        y += 22;

        Controls.Add(GlassHelper.CreateLabel("Посл. heartbeat", left, y, 140, secondary: true));
        _lastHeartbeatValue = GlassHelper.CreateLabel("—", valX, y, 280);
        Controls.Add(_lastHeartbeatValue);
        y += 28;

        // Separator
        Controls.Add(GlassHelper.CreateSeparator(left, y, fullWidth));
        y += 12;

        // ── Settings Section ──
        Controls.Add(GlassHelper.CreateSectionHeader("Настройки подключения", left, y));
        y += 28;

        Controls.Add(GlassHelper.CreateLabel("Адрес сервера", left, y, 200, secondary: true));
        y += 20;
        _serverUrlBox = GlassHelper.CreateTextBox(left, y, fullWidth);
        Controls.Add(_serverUrlBox);
        y += 36;

        Controls.Add(GlassHelper.CreateLabel("Токен регистрации", left, y, 200, secondary: true));
        y += 20;
        _tokenBox = GlassHelper.CreateTextBox(left, y, fullWidth, placeholder: "drt_... (оставьте пустым для текущего)");
        Controls.Add(_tokenBox);
        y += 38;

        _reconnectBtn = GlassHelper.CreateAccentButton("Переподключиться", left, y);
        _reconnectBtn.Click += OnReconnect;
        Controls.Add(_reconnectBtn);

        _statusMessage = new Label
        {
            Location = new Point(left + 170, y + 8),
            Size = new Size(260, 25),
            ForeColor = GlassHelper.TextSecondary,
            BackColor = Color.Transparent,
            Font = new Font("Segoe UI", 9),
            Text = ""
        };
        Controls.Add(_statusMessage);
    }

    private void StartAutoRefresh()
    {
        _refreshTimer = new System.Threading.Timer(
            callback: _ => RefreshStatusOnThreadPool(),
            state: null,
            dueTime: 0,
            period: 2000);
    }

    /// <summary>
    /// Runs on thread pool thread. All pipe I/O completes here.
    /// </summary>
    private void RefreshStatusOnThreadPool()
    {
        if (Interlocked.CompareExchange(ref _refreshing, 1, 0) != 0)
            return;

        try
        {
            if (IsDisposed) return;

            if (!_pipeClient.IsConnected)
            {
                var connected = _pipeClient.ConnectAsync(1000).GetAwaiter().GetResult();
                if (!connected)
                {
                    // Check actual Windows service state
                    var svcState = QueryServiceState();
                    MarshalSetDisconnectedState(svcState);
                    return;
                }
            }

            var status = _pipeClient.GetStatusAsync().GetAwaiter().GetResult();
            if (status == null)
            {
                var svcState = QueryServiceState();
                MarshalSetDisconnectedState(svcState);
                return;
            }
            MarshalUpdateUI(status);
        }
        catch
        {
            MarshalSetDisconnectedState("unknown");
        }
        finally
        {
            Interlocked.Exchange(ref _refreshing, 0);
        }
    }

    /// <summary>
    /// Query Windows service state via WMI. Runs on thread pool.
    /// Returns: "Running", "Stopped", "Start Pending", "Stop Pending", "not_installed", "error"
    /// </summary>
    private static string QueryServiceState()
    {
        try
        {
            using var searcher = new ManagementObjectSearcher(
                "SELECT State FROM Win32_Service WHERE Name = 'KaderoAgent'");
            foreach (ManagementObject obj in searcher.Get())
            {
                var state = obj["State"]?.ToString() ?? "unknown";
                obj.Dispose();
                return state;
            }
            return "not_installed";
        }
        catch
        {
            return "error";
        }
    }

    private void MarshalSetDisconnectedState(string serviceState)
    {
        if (IsDisposed) return;
        try { BeginInvoke(() => SetDisconnectedState(serviceState)); }
        catch (ObjectDisposedException) { }
        catch (InvalidOperationException) { }
    }

    private void MarshalUpdateUI(AgentStatus status)
    {
        if (IsDisposed) return;
        try { BeginInvoke(() => UpdateUI(status)); }
        catch (ObjectDisposedException) { }
        catch (InvalidOperationException) { }
    }

    private void SetDisconnectedState(string serviceState)
    {
        _connectionStatusLabel.Text = "Нет связи с сервисом";
        _connectionIndicator.BackColor = GlassHelper.StatusGray;
        _connectionIndicator.Invalidate();
        _recordingStatusLabel.Text = "Неизвестно";
        _recordingIndicator.BackColor = GlassHelper.StatusGray;
        _recordingIndicator.Invalidate();

        UpdateServiceStatus(serviceState);
    }

    private void UpdateServiceStatus(string serviceState)
    {
        switch (serviceState)
        {
            case "Running":
                _serviceStatusLabel.Text = "Служба: запущена";
                _serviceIndicator.BackColor = GlassHelper.StatusGreen;
                ShowProgress(false);
                break;
            case "Start Pending":
            case "Continue Pending":
                _serviceStatusLabel.Text = "Служба: запускается...";
                _serviceIndicator.BackColor = GlassHelper.StatusYellow;
                ShowProgress(true);
                break;
            case "Stop Pending":
            case "Pause Pending":
                _serviceStatusLabel.Text = "Служба: останавливается...";
                _serviceIndicator.BackColor = GlassHelper.StatusYellow;
                ShowProgress(true);
                break;
            case "Stopped":
            case "Paused":
                _serviceStatusLabel.Text = "Служба: остановлена";
                _serviceIndicator.BackColor = GlassHelper.TextPrimary; // White
                ShowProgress(false);
                break;
            case "not_installed":
                _serviceStatusLabel.Text = "Служба: не установлена";
                _serviceIndicator.BackColor = GlassHelper.StatusGray;
                ShowProgress(false);
                break;
            default: // "error", "unknown"
                _serviceStatusLabel.Text = "Служба: ошибка";
                _serviceIndicator.BackColor = GlassHelper.StatusRed;
                ShowProgress(false);
                break;
        }
        _serviceIndicator.Invalidate();
    }

    private void ShowProgress(bool show)
    {
        _progressPanel.Visible = show;
        if (show)
        {
            if (_progressAnimTimer == null)
            {
                _progressAnimTimer = new System.Windows.Forms.Timer { Interval = 40 };
                _progressAnimTimer.Tick += (_, _) =>
                {
                    _progressOffset = (_progressOffset + 4) % (_progressPanel.Width + 120);
                    _progressPanel.Invalidate();
                };
            }
            _progressAnimTimer.Start();
        }
        else
        {
            _progressAnimTimer?.Stop();
        }
    }

    private void PaintProgressBar(object? sender, PaintEventArgs e)
    {
        var w = _progressPanel.Width;
        var h = _progressPanel.Height;

        // Background track
        using var bgBrush = new SolidBrush(Color.FromArgb(30, 255, 255, 255));
        e.Graphics.FillRectangle(bgBrush, 0, 0, w, h);

        // Moving bar
        var barWidth = w / 4;
        var x = _progressOffset - barWidth;
        if (x < w)
        {
            var drawX = Math.Max(0, x);
            var drawW = Math.Min(barWidth, w - drawX);
            if (x < 0) drawW = barWidth + x;
            using var barBrush = new SolidBrush(GlassHelper.StatusYellow);
            e.Graphics.FillRectangle(barBrush, drawX, 0, drawW, h);
        }
    }

    private void UpdateUI(AgentStatus status)
    {
        // Connection
        switch (status.ConnectionStatus)
        {
            case "connected":
                _connectionStatusLabel.Text = "Подключен";
                _connectionIndicator.BackColor = GlassHelper.StatusGreen;
                break;
            case "reconnecting":
                _connectionStatusLabel.Text = "Переподключение...";
                _connectionIndicator.BackColor = GlassHelper.StatusOrange;
                break;
            case "error":
                _connectionStatusLabel.Text = $"Ошибка: {status.LastError ?? "неизвестная"}";
                _connectionIndicator.BackColor = GlassHelper.StatusRed;
                break;
            default:
                _connectionStatusLabel.Text = "Отключен";
                _connectionIndicator.BackColor = GlassHelper.StatusGray;
                break;
        }
        _connectionIndicator.Invalidate();

        // Recording / Agent State — use AgentStateDisplay if available, fallback to RecordingStatus
        var stateDisplay = !string.IsNullOrEmpty(status.AgentStateDisplay) ? status.AgentStateDisplay : null;
        var stateName = !string.IsNullOrEmpty(status.AgentStateName) ? status.AgentStateName : status.RecordingStatus;

        switch (stateName)
        {
            case "recording":
                _recordingStatusLabel.Text = stateDisplay ?? "Запись идёт";
                _recordingIndicator.BackColor = GlassHelper.StatusGreen;
                break;
            case "online":
                _recordingStatusLabel.Text = stateDisplay ?? "Онлайн";
                _recordingIndicator.BackColor = GlassHelper.StatusGreen;
                break;
            case "recording_disabled":
                _recordingStatusLabel.Text = stateDisplay ?? "Запись отключена администратором";
                _recordingIndicator.BackColor = GlassHelper.StatusYellow;
                break;
            case "idle":
                _recordingStatusLabel.Text = stateDisplay ?? "Пользователь неактивен";
                _recordingIndicator.BackColor = GlassHelper.StatusYellow;
                break;
            case "awaiting_user":
                _recordingStatusLabel.Text = stateDisplay ?? "Ожидание входа пользователя";
                _recordingIndicator.BackColor = GlassHelper.StatusYellow;
                break;
            case "configuring":
            case "starting":
                _recordingStatusLabel.Text = stateDisplay ?? "Запуск...";
                _recordingIndicator.BackColor = GlassHelper.StatusYellow;
                break;
            case "error":
                _recordingStatusLabel.Text = stateDisplay ?? "Ошибка записи";
                _recordingIndicator.BackColor = GlassHelper.StatusRed;
                break;
            case "stopped":
                _recordingStatusLabel.Text = stateDisplay ?? "Остановка...";
                _recordingIndicator.BackColor = GlassHelper.StatusGray;
                break;
            default:
                _recordingStatusLabel.Text = stateDisplay ?? "Остановлена";
                _recordingIndicator.BackColor = GlassHelper.StatusGray;
                break;
        }
        _recordingIndicator.Invalidate();

        // Service: pipe connected = service running
        UpdateServiceStatus("Running");

        _deviceIdValue.Text = status.DeviceId ?? "—";
        _fpsValue.Text = status.CaptureFps > 0 ? $"{status.CaptureFps} кадров/сек" : "—";
        _qualityValue.Text = status.Quality switch
        {
            "low" => "Низкое",
            "medium" => "Среднее",
            "high" => "Высокое",
            _ => status.Quality ?? "—"
        };
        _resolutionValue.Text = !string.IsNullOrEmpty(status.Resolution) ? status.Resolution : "—";
        _segmentValue.Text = status.SegmentDurationSec > 0 ? $"{status.SegmentDurationSec} сек" : "—";
        _heartbeatValue.Text = status.HeartbeatIntervalSec > 0 ? $"{status.HeartbeatIntervalSec} сек" : "—";
        _autoStartValue.Text = status.AutoStart ? "Да" : "Нет";
        // Prefer SessionMaxDurationMin; fallback to Hours for backward compat
        if (status.SessionMaxDurationMin > 0)
            _maxSessionValue.Text = $"{status.SessionMaxDurationMin} мин";
        else if (status.SessionMaxDurationHours > 0)
            _maxSessionValue.Text = $"{status.SessionMaxDurationHours} ч";
        else
            _maxSessionValue.Text = "---";

        _cpuValue.Text = $"{status.CpuPercent:F1}%";
        _memoryValue.Text = $"{status.MemoryMb:F0} МБ";
        _diskValue.Text = $"{status.DiskFreeGb:F1} ГБ";
        _queueValue.Text = $"{status.SegmentsQueued}";
        _lastHeartbeatValue.Text = status.LastHeartbeatTs?.ToLocalTime().ToString("dd.MM.yyyy HH:mm:ss") ?? "—";

        if (string.IsNullOrEmpty(_serverUrlBox.Text) && !string.IsNullOrEmpty(status.ServerUrl))
            _serverUrlBox.Text = status.ServerUrl;
    }

    private void OnReconnect(object? sender, EventArgs e)
    {
        _reconnectBtn.Enabled = false;
        _statusMessage.Text = "Переподключение...";
        _statusMessage.ForeColor = GlassHelper.TextSecondary;

        var serverUrl = _serverUrlBox.Text.Trim();
        var token = string.IsNullOrWhiteSpace(_tokenBox.Text) ? null : _tokenBox.Text.Trim();

        // Pipe I/O on thread pool, marshal result back to UI
        ThreadPool.QueueUserWorkItem(_ =>
        {
            try
            {
                if (!_pipeClient.IsConnected)
                    _pipeClient.ConnectAsync().GetAwaiter().GetResult();

                var response = _pipeClient.ReconnectAsync(serverUrl, token).GetAwaiter().GetResult();

                if (IsDisposed) return;
                BeginInvoke(() =>
                {
                    if (response?.Success == true)
                    {
                        _statusMessage.Text = "Подключено!";
                        _statusMessage.ForeColor = GlassHelper.StatusGreen;
                        _tokenBox.Text = "";
                        _onReconnect?.Invoke();
                    }
                    else
                    {
                        _statusMessage.Text = response?.Error ?? "Ошибка подключения";
                        _statusMessage.ForeColor = GlassHelper.StatusRed;
                    }
                    _reconnectBtn.Enabled = true;
                });
            }
            catch (Exception ex)
            {
                if (IsDisposed) return;
                try
                {
                    BeginInvoke(() =>
                    {
                        _statusMessage.Text = $"Ошибка: {ex.Message}";
                        _statusMessage.ForeColor = GlassHelper.StatusRed;
                        _reconnectBtn.Enabled = true;
                    });
                }
                catch { }
            }
        });
    }

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        if (e.CloseReason == CloseReason.UserClosing)
        {
            e.Cancel = true;
            _refreshTimer?.Change(Timeout.Infinite, Timeout.Infinite); // Stop timer while hidden
            _progressAnimTimer?.Stop();
            Hide();
            return;
        }
        _refreshTimer?.Dispose();
        _refreshTimer = null;
        _progressAnimTimer?.Dispose();
        _progressAnimTimer = null;
        base.OnFormClosing(e);
    }

    protected override void OnVisibleChanged(EventArgs e)
    {
        base.OnVisibleChanged(e);
        if (Visible)
        {
            // Resume timer when shown
            _refreshTimer?.Change(0, 2000);
        }
    }
}
