using System.Windows.Forms;
using KaderoAgent.Auth;
using KaderoAgent.Configuration;
using Microsoft.Extensions.DependencyInjection;

namespace KaderoAgent.Tray;

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
        InitializeComponent();
    }

    private void InitializeComponent()
    {
        Text = "Кадеро — Настройка агента";
        Size = new System.Drawing.Size(450, 300);
        StartPosition = FormStartPosition.CenterScreen;
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;

        var y = 20;

        var serverLabel = new Label { Text = "Адрес сервера:", Location = new(20, y), AutoSize = true };
        Controls.Add(serverLabel);
        y += 25;

        _serverUrlBox = new TextBox
        {
            Location = new(20, y), Size = new(390, 25),
            Text = "https://services-test.shepaland.ru/screenrecorder"
        };
        Controls.Add(_serverUrlBox);
        y += 35;

        var tokenLabel = new Label { Text = "Токен регистрации:", Location = new(20, y), AutoSize = true };
        Controls.Add(tokenLabel);
        y += 25;

        _tokenBox = new TextBox { Location = new(20, y), Size = new(390, 25), PlaceholderText = "drt_..." };
        Controls.Add(_tokenBox);
        y += 45;

        _connectBtn = new Button
        {
            Text = "Подключить", Location = new(150, y), Size = new(130, 35)
        };
        _connectBtn.Click += OnConnect;
        Controls.Add(_connectBtn);
        y += 50;

        _statusLabel = new Label
        {
            Text = "", Location = new(20, y), Size = new(390, 40),
            ForeColor = System.Drawing.Color.Red
        };
        Controls.Add(_statusLabel);
    }

    private async void OnConnect(object? sender, EventArgs e)
    {
        _connectBtn.Enabled = false;
        _statusLabel.Text = "Подключение...";
        _statusLabel.ForeColor = System.Drawing.Color.Gray;

        try
        {
            var authManager = _services.GetRequiredService<AuthManager>();
            var response = await authManager.RegisterAsync(_serverUrlBox.Text.Trim(), _tokenBox.Text.Trim());

            _statusLabel.Text = $"Устройство зарегистрировано: {response.DeviceId}";
            _statusLabel.ForeColor = System.Drawing.Color.Green;

            await Task.Delay(2000);
            Close();
        }
        catch (Exception ex)
        {
            _statusLabel.Text = $"Ошибка: {ex.Message}";
            _statusLabel.ForeColor = System.Drawing.Color.Red;
            _connectBtn.Enabled = true;
        }
    }
}
