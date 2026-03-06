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
/// </summary>
public class SetupForm : Form
{
    private readonly IServiceProvider _services;
    private TextBox _serverUrlBox = null!;
    private TextBox _tokenBox = null!;
    private Button _connectBtn = null!;
    private Label _statusLabel = null!;

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
        Size = new Size(420, 320);
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

        // Token
        Controls.Add(GlassHelper.CreateLabel("Токен регистрации", left, y, 200, secondary: true));
        y += 22;
        _tokenBox = GlassHelper.CreateTextBox(left, y, fieldWidth, placeholder: "drt_...");
        Controls.Add(_tokenBox);
        y += 48;

        // Connect button (centered)
        var btnWidth = 160;
        _connectBtn = GlassHelper.CreateAccentButton("Подключить", (Width - btnWidth) / 2, y, btnWidth, 38);
        _connectBtn.Click += OnConnect;
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
                    _tokenBox.Text = pending.RegistrationToken;
            }
        }
        catch { /* ignore if not available */ }
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
            _connectBtn.Enabled = true;
        }
    }
}
