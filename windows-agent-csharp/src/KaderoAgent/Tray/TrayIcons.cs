namespace KaderoAgent.Tray;

using System.Drawing;
using System.Drawing.Drawing2D;
using KaderoAgent.Resources;

/// <summary>
/// Generates tray icons programmatically using LogoHelper.
/// Icons are cached (Lazy) to avoid GDI handle leaks from repeated creation.
/// Shows "K" logo with status dot: green = recording/connected, yellow = idle, red = disconnected, gray = unknown.
/// </summary>
public static class TrayIcons
{
    private static readonly Lazy<Icon> _connected = new(() => LogoHelper.CreateTrayIcon(GlassHelper.StatusGreen));
    private static readonly Lazy<Icon> _recording = new(() => LogoHelper.CreateTrayIcon(GlassHelper.StatusGreen));
    private static readonly Lazy<Icon> _idle = new(() => LogoHelper.CreateTrayIcon(GlassHelper.StatusYellow));
    private static readonly Lazy<Icon> _disconnected = new(() => LogoHelper.CreateTrayIcon(GlassHelper.StatusRed));
    private static readonly Lazy<Icon> _unknown = new(() => LogoHelper.CreateTrayIcon(GlassHelper.StatusGray));
    private static readonly Lazy<Icon> _reconnecting = new(() => LogoHelper.CreateTrayIcon(GlassHelper.StatusOrange));

    public static Icon Connected => _connected.Value;
    public static Icon Recording => _recording.Value;
    public static Icon Idle => _idle.Value;
    public static Icon Disconnected => _disconnected.Value;
    public static Icon Unknown => _unknown.Value;
    public static Icon Reconnecting => _reconnecting.Value;
}
