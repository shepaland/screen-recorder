using System.Windows.Forms;
using System.Drawing;
using KaderoAgent.Auth;
using KaderoAgent.Configuration;
using KaderoAgent.Resources;
using Microsoft.Extensions.DependencyInjection;

namespace KaderoAgent.Tray;

/// <summary>
/// Setup form with glass/acrylic UI style.
/// Shown on first launch for device registration.
/// Fields: Server URL, Token (masked with toggle), Validation indicator, Connect button.
/// </summary>
public class SetupForm : Form
{
    private readonly IServiceProvider _services;
    private TextBox _serverUrlBox = null!;
    private TextBox _tokenBox = null!;
    private Label _toggleTokenBtn = null!;
    private Panel _validationIndicator = null!;
    private Label _validationLabel = null!;
    private Button _connectBtn = null!;
    private Label _statusLabel = null!;

    // Debounce timer for token validation
    private System.Windows.Forms.Timer _debounceTimer = null!;
    private bool _tokenValid;
    private CancellationTokenSource? _validationCts;

    public SetupForm(IServiceProvider services)
    {
        _services = services;
        SetStyle(ControlStyles.SupportsTransparentBackColor | ControlStyles.OptimizedDoubleBuffer, true);
        InitializeComponent();
        LoadPendingRegistration();
    }

    private void InitializeComponent()
    {
        Text = "Кадеро — Настройка агента";
        Size = new Size(420, 360);
        MaximizeBox = false;
        ShowInTaskbar = true;
        Icon = LogoHelper.CreateAppIcon(32);

        // Glass effect
        GlassHelper.ApplyGlassEffect(this);
        GlassHelper.PositionCenter(this);

        // Paint border
        Paint += (_, e) => GlassHelper.PaintGlassBorder(this, e);

        // ── Title bar ──
        var titleBar = GlassHelper.CreateTitleBar(this, "Настройка агента", Width);
        Controls.Add(titleBar);
        GlassHelper.EnableDrag(this);

        var y = 52;
        var left = 24;
        var fieldWidth = Width - 48;

        // Server URL
        Controls.Add(GlassHelper.CreateLabel("Адрес сервера", left, y, 200, secondary: true));
        y += 22;
        _serverUrlBox = GlassHelper.CreateTextBox(left, y, fieldWidth);
        _serverUrlBox.Text = "https://";
        Controls.Add(_serverUrlBox);
        y += 40;

        // Token (masked)
        Controls.Add(GlassHelper.CreateLabel("Токен регистрации", left, y, 200, secondary: true));
        y += 22;

        // Token field -- reserve space for toggle button on the right
        var toggleBtnWidth = 30;
        _tokenBox = GlassHelper.CreateTextBox(left, y, fieldWidth - toggleBtnWidth - 4, placeholder: "drt_...");
        _tokenBox.UseSystemPasswordChar = true;
        _tokenBox.TextChanged += OnTokenTextChanged;
        _tokenBox.Leave += OnTokenLeaveFocus;
        Controls.Add(_tokenBox);

        // Toggle show/hide button
        _toggleTokenBtn = new Label
        {
            Text = "\U0001F441", // eye icon
            Location = new Point(left + fieldWidth - toggleBtnWidth, y),
            Size = new Size(toggleBtnWidth, 28),
            ForeColor = GlassHelper.TextSecondary,
            BackColor = Color.FromArgb(45, 45, 50),
            Font = new Font("Segoe UI", 11),
            TextAlign = ContentAlignment.MiddleCenter,
            Cursor = Cursors.Hand,
            BorderStyle = BorderStyle.FixedSingle
        };
        _toggleTokenBtn.Click += OnToggleTokenVisibility;
        _toggleTokenBtn.MouseEnter += (_, _) => _toggleTokenBtn.ForeColor = GlassHelper.TextPrimary;
        _toggleTokenBtn.MouseLeave += (_, _) => _toggleTokenBtn.ForeColor = GlassHelper.TextSecondary;
        Controls.Add(_toggleTokenBtn);
        y += 34;

        // Validation indicator row
        _validationIndicator = new Panel
        {
            Location = new Point(left, y + 2),
            Size = new Size(12, 12),
            BackColor = Color.Transparent
        };
        _validationIndicator.Paint += (_, e) =>
        {
            if (_validationIndicator.BackColor == Color.Transparent) return;
            e.Graphics.SmoothingMode = System.Drawing.Drawing2D.SmoothingMode.AntiAlias;
            using var brush = new SolidBrush(_validationIndicator.BackColor);
            e.Graphics.FillEllipse(brush, 0, 0, 11, 11);
        };
        Controls.Add(_validationIndicator);

        _validationLabel = new Label
        {
            Text = "",
            Location = new Point(left + 18, y),
            Size = new Size(fieldWidth - 18, 18),
            ForeColor = GlassHelper.TextSecondary,
            BackColor = Color.Transparent,
            Font = new Font("Segoe UI", 8.5f)
        };
        Controls.Add(_validationLabel);
        y += 30;

        // Connect button (centered, initially disabled)
        var btnWidth = 160;
        _connectBtn = GlassHelper.CreateAccentButton("Подключить", (Width - btnWidth) / 2, y, btnWidth, 38);
        _connectBtn.Click += OnConnect;
        _connectBtn.Enabled = false;
        Controls.Add(_connectBtn);
        y += 52;

        // Status
        _statusLabel = new Label
        {
            Text = "",
            Location = new Point(left, y),
            Size = new Size(fieldWidth, 40),
            ForeColor = GlassHelper.StatusRed,
            BackColor = Color.Transparent,
            Font = new Font("Segoe UI", 9),
            TextAlign = ContentAlignment.MiddleCenter
        };
        Controls.Add(_statusLabel);

        // Debounce timer (500ms after last keystroke)
        _debounceTimer = new System.Windows.Forms.Timer { Interval = 500 };
        _debounceTimer.Tick += OnDebounceTimerTick;
    }

    private void LoadPendingRegistration()
    {
        try
        {
            var credStore = _services.GetRequiredService<CredentialStore>();
            var pending = credStore.LoadPendingRegistration();
            if (pending != null)
            {
                if (!string.IsNullOrEmpty(pending.ServerUrl))
                    _serverUrlBox.Text = pending.ServerUrl;
                if (!string.IsNullOrEmpty(pending.RegistrationToken))
                {
                    _tokenBox.Text = pending.RegistrationToken;
                    // Trigger validation for pre-loaded token
                    _debounceTimer.Stop();
                    _debounceTimer.Start();
                }
            }
        }
        catch { /* ignore if not available */ }
    }

    private void OnToggleTokenVisibility(object? sender, EventArgs e)
    {
        _tokenBox.UseSystemPasswordChar = !_tokenBox.UseSystemPasswordChar;
        _toggleTokenBtn.ForeColor = _tokenBox.UseSystemPasswordChar
            ? GlassHelper.TextSecondary
            : GlassHelper.AccentColor;
    }

    private void OnTokenTextChanged(object? sender, EventArgs e)
    {
        // Reset validation state on each keystroke
        _tokenValid = false;
        _connectBtn.Enabled = false;

        // Restart debounce timer
        _debounceTimer.Stop();

        var token = _tokenBox.Text.Trim();
        if (string.IsNullOrEmpty(token))
        {
            SetValidationState(ValidationState.None, "");
            return;
        }

        _debounceTimer.Start();
    }

    private void OnTokenLeaveFocus(object? sender, EventArgs e)
    {
        // Validate immediately on focus loss
        var token = _tokenBox.Text.Trim();
        if (!string.IsNullOrEmpty(token) && !_tokenValid)
        {
            _debounceTimer.Stop();
            _ = ValidateTokenAsync();
        }
    }

    private void OnDebounceTimerTick(object? sender, EventArgs e)
    {
        _debounceTimer.Stop();
        _ = ValidateTokenAsync();
    }

    private enum ValidationState
    {
        None,
        Loading,
        Valid,
        Invalid
    }

    private void SetValidationState(ValidationState state, string text)
    {
        if (InvokeRequired) { Invoke(() => SetValidationState(state, text)); return; }

        switch (state)
        {
            case ValidationState.None:
                _validationIndicator.BackColor = Color.Transparent;
                _validationLabel.Text = "";
                _validationLabel.ForeColor = GlassHelper.TextSecondary;
                break;
            case ValidationState.Loading:
                _validationIndicator.BackColor = GlassHelper.StatusGray;
                _validationLabel.Text = text;
                _validationLabel.ForeColor = GlassHelper.TextSecondary;
                break;
            case ValidationState.Valid:
                _validationIndicator.BackColor = GlassHelper.StatusGreen;
                _validationLabel.Text = text;
                _validationLabel.ForeColor = GlassHelper.StatusGreen;
                break;
            case ValidationState.Invalid:
                _validationIndicator.BackColor = GlassHelper.StatusRed;
                _validationLabel.Text = text;
                _validationLabel.ForeColor = GlassHelper.StatusRed;
                break;
        }
        _validationIndicator.Invalidate();
    }

    private async Task ValidateTokenAsync()
    {
        var serverUrl = _serverUrlBox.Text.Trim();
        var token = _tokenBox.Text.Trim();

        if (string.IsNullOrEmpty(token))
        {
            SetValidationState(ValidationState.None, "");
            return;
        }

        if (string.IsNullOrEmpty(serverUrl) || !serverUrl.StartsWith("http"))
        {
            SetValidationState(ValidationState.Invalid, "Укажите адрес сервера");
            return;
        }

        // Cancel previous validation if still in progress
        _validationCts?.Cancel();
        _validationCts = new CancellationTokenSource();
        var ct = _validationCts.Token;

        SetValidationState(ValidationState.Loading, "Проверка...");

        try
        {
            var authManager = _services.GetRequiredService<AuthManager>();
            var result = await authManager.ValidateTokenAsync(serverUrl, token);

            if (ct.IsCancellationRequested) return;

            if (result.Valid)
            {
                var displayText = $"{result.TenantName ?? "?"} / {result.TokenName ?? "?"}";
                SetValidationState(ValidationState.Valid, displayText);
                _tokenValid = true;
                if (InvokeRequired)
                    Invoke(() => _connectBtn.Enabled = true);
                else
                    _connectBtn.Enabled = true;
            }
            else
            {
                var reason = result.Reason switch
                {
                    "INVALID_TOKEN" => "Неверный токен",
                    "TOKEN_INACTIVE" => "Токен деактивирован",
                    "TOKEN_EXPIRED" => "Токен просрочен",
                    "TOKEN_EXHAUSTED" => "Лимит использования исчерпан",
                    _ => result.Reason ?? "Неизвестная ошибка"
                };
                SetValidationState(ValidationState.Invalid, reason);
                _tokenValid = false;
                if (InvokeRequired)
                    Invoke(() => _connectBtn.Enabled = false);
                else
                    _connectBtn.Enabled = false;
            }
        }
        catch (Exception ex)
        {
            if (ct.IsCancellationRequested) return;
            SetValidationState(ValidationState.Invalid, $"Ошибка: {ex.Message}");
            _tokenValid = false;
            if (InvokeRequired)
                Invoke(() => _connectBtn.Enabled = false);
            else
                _connectBtn.Enabled = false;
        }
    }

    private async void OnConnect(object? sender, EventArgs e)
    {
        _connectBtn.Enabled = false;
        _statusLabel.Text = "Подключение...";
        _statusLabel.ForeColor = GlassHelper.TextSecondary;

        try
        {
            var authManager = _services.GetRequiredService<AuthManager>();
            var response = await authManager.RegisterAsync(_serverUrlBox.Text.Trim(), _tokenBox.Text.Trim());

            var credStore = _services.GetRequiredService<CredentialStore>();
            credStore.ClearPendingRegistration();

            _statusLabel.Text = $"Устройство зарегистрировано: {response.DeviceId}";
            _statusLabel.ForeColor = GlassHelper.StatusGreen;

            await Task.Delay(2000);
            Close();
        }
        catch (Exception ex)
        {
            _statusLabel.Text = $"Ошибка: {ex.Message}";
            _statusLabel.ForeColor = GlassHelper.StatusRed;
            _connectBtn.Enabled = _tokenValid;
        }
    }

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        _debounceTimer?.Stop();
        _debounceTimer?.Dispose();
        _validationCts?.Cancel();
        _validationCts?.Dispose();
        base.OnFormClosing(e);
    }
}
