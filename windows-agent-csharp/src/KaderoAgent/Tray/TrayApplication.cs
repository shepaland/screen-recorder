using System.Windows.Forms;
using KaderoAgent.Auth;
using KaderoAgent.Capture;
using Microsoft.Extensions.DependencyInjection;

namespace KaderoAgent.Tray;

public class TrayApplication : ApplicationContext
{
    private readonly NotifyIcon _trayIcon;
    private readonly IServiceProvider _services;

    public TrayApplication(IServiceProvider services)
    {
        _services = services;

        _trayIcon = new NotifyIcon
        {
            Icon = SystemIcons.Application,
            Text = "Кадеро Agent",
            Visible = true,
            ContextMenuStrip = CreateMenu()
        };
    }

    private ContextMenuStrip CreateMenu()
    {
        var menu = new ContextMenuStrip();

        var statusItem = new ToolStripMenuItem("Статус: Подключен") { Enabled = false };
        menu.Items.Add(statusItem);
        menu.Items.Add(new ToolStripSeparator());

        var settingsItem = new ToolStripMenuItem("Настройки...");
        settingsItem.Click += (_, _) =>
        {
            var form = new SetupForm(_services);
            form.ShowDialog();
        };
        menu.Items.Add(settingsItem);

        menu.Items.Add(new ToolStripSeparator());

        var exitItem = new ToolStripMenuItem("Выход");
        exitItem.Click += (_, _) =>
        {
            _trayIcon.Visible = false;
            Application.Exit();
        };
        menu.Items.Add(exitItem);

        return menu;
    }
}
