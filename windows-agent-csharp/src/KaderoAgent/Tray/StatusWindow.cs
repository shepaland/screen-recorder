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

    // Device info
    private Label _deviceIdValue = null!;

    // Metrics
    private Label _cpuValue = null!;
    private Label _memoryValue = null!;
    private Label _diskValue = null!;
    private Label _lastHeartbeatValue = null!;

    // Queue indicators
    private Panel _segmentsQueueIndicator = null!;
    private Label _segmentsQueueLabel = null!;
    private Panel _auditQueueIndicator = null!;
    private Label _auditQueueLabel = null!;
    private Panel _focusQueueIndicator = null!;
    private Label _focusQueueLabel = null!;
    private Panel _inputQueueIndicator = null!;
    private Label _inputQueueLabel = null!;

    // Connection info (read-only)
    private TextBox _serverUrlBox = null!;
    private Panel _registrationIndicator = null!;
    private Label _registrationLabel = null!;

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
        Size = new Size(480, 580);
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
        y += 22;

        Controls.Add(GlassHelper.CreateLabel("Посл. heartbeat", left, y, 140, secondary: true));
        _lastHeartbeatValue = GlassHelper.CreateLabel("—", valX, y, 280);
        Controls.Add(_lastHeartbeatValue);
        y += 28;

        // Separator
        Controls.Add(GlassHelper.CreateSeparator(left, y, fullWidth));
        y += 12;

        // ── Queues Section ──
        Controls.Add(GlassHelper.CreateSectionHeader("Очереди", left, y));
        y += 28;

        _segmentsQueueIndicator = GlassHelper.CreateIndicator(left, y, GlassHelper.StatusGreen);
        Controls.Add(_segmentsQueueIndicator);
        _segmentsQueueLabel = GlassHelper.CreateLabel("Сегменты: —", left + 22, y, 200);
        Controls.Add(_segmentsQueueLabel);

        _auditQueueIndicator = GlassHelper.CreateIndicator(left + 220, y, GlassHelper.StatusGreen);
        Controls.Add(_auditQueueIndicator);
        _auditQueueLabel = GlassHelper.CreateLabel("Аудит: —", left + 242, y, 200);
        Controls.Add(_auditQueueLabel);
        y += 22;

        _focusQueueIndicator = GlassHelper.CreateIndicator(left, y, GlassHelper.StatusGreen);
        Controls.Add(_focusQueueIndicator);
        _focusQueueLabel = GlassHelper.CreateLabel("Фокус: —", left + 22, y, 200);
        Controls.Add(_focusQueueLabel);

        _inputQueueIndicator = GlassHelper.CreateIndicator(left + 220, y, GlassHelper.StatusGreen);
        Controls.Add(_inputQueueIndicator);
        _inputQueueLabel = GlassHelper.CreateLabel("Ввод: —", left + 242, y, 200);
        Controls.Add(_inputQueueLabel);
        y += 28;

        // Separator
        Controls.Add(GlassHelper.CreateSeparator(left, y, fullWidth));
        y += 12;

        // ── Connection Info Section ──
        Controls.Add(GlassHelper.CreateSectionHeader("Подключение к серверу", left, y));
        y += 28;

        // Registration status indicator
        _registrationIndicator = GlassHelper.CreateIndicator(left, y, GlassHelper.StatusGreen);
        Controls.Add(_registrationIndicator);
        _registrationLabel = GlassHelper.CreateLabel("Агент зарегистрирован", left + 22, y, 300);
        Controls.Add(_registrationLabel);
        y += 28;

        Controls.Add(GlassHelper.CreateLabel("Адрес сервера", left, y, 200, secondary: true));
        y += 20;
        _serverUrlBox = GlassHelper.CreateTextBox(left, y, fullWidth);
        _serverUrlBox.ReadOnly = true;
        _serverUrlBox.TabStop = false;
        _serverUrlBox.Cursor = Cursors.Default;
        Controls.Add(_serverUrlBox);
        y += 36;

        var infoLabel = GlassHelper.CreateLabel("Изменение сервера — только через переустановку агента", left, y, fullWidth, secondary: true);
        infoLabel.Font = new Font("Segoe UI", 7.5f, FontStyle.Italic);
        Controls.Add(infoLabel);
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
                _recordingStatusLabel.Text = "Включено";
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

        _cpuValue.Text = $"{status.CpuPercent:F1}%";
        _memoryValue.Text = $"{status.MemoryMb:F0} МБ";
        _diskValue.Text = $"{status.DiskFreeGb:F1} ГБ";
        _lastHeartbeatValue.Text = status.LastHeartbeatTs?.ToLocalTime().ToString("dd.MM.yyyy HH:mm:ss") ?? "—";

        // Queues
        UpdateQueueRow(_segmentsQueueIndicator, _segmentsQueueLabel, "Сегменты", status.SegmentsQueued);
        UpdateQueueRow(_auditQueueIndicator, _auditQueueLabel, "Аудит", status.AuditEventsQueued);
        UpdateQueueRow(_focusQueueIndicator, _focusQueueLabel, "Фокус", status.FocusIntervalsQueued);
        UpdateQueueRow(_inputQueueIndicator, _inputQueueLabel, "Ввод", status.InputEventsQueued);

        // Upload error: override recording indicator to orange
        if (status.UploadError)
        {
            _recordingIndicator.BackColor = GlassHelper.StatusOrange;
            _recordingIndicator.Invalidate();
            _recordingStatusLabel.Text = status.UploadErrorMessage ?? "Ошибка загрузки";
        }

        // Server URL (read-only)
        if (!string.IsNullOrEmpty(status.ServerUrl))
            _serverUrlBox.Text = status.ServerUrl;

        // Registration status
        if (status.ConnectionStatus == "connected" && !string.IsNullOrEmpty(status.DeviceId))
        {
            _registrationLabel.Text = "Агент зарегистрирован";
            _registrationIndicator.BackColor = GlassHelper.StatusGreen;
        }
        else
        {
            _registrationLabel.Text = "Не зарегистрирован";
            _registrationIndicator.BackColor = GlassHelper.StatusRed;
        }
        _registrationIndicator.Invalidate();
    }

    private void UpdateQueueRow(Panel indicator, Label label, string name, int count)
    {
        label.Text = $"{name}: {count}";
        indicator.BackColor = count switch
        {
            0 => GlassHelper.StatusGreen,
            <= 50 => GlassHelper.StatusYellow,
            _ => GlassHelper.StatusRed
        };
        indicator.Invalidate();
    }

    // OnReconnect removed — server URL and token changes only via reinstall

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
