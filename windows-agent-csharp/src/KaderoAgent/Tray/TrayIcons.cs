namespace KaderoAgent.Tray;

using System.Drawing;
using System.Drawing.Drawing2D;
using KaderoAgent.Resources;

/// <summary>
/// Generates tray icons programmatically using LogoHelper.
/// Shows "K" logo with status dot: green = recording/connected, yellow = idle, red = disconnected, gray = unknown.
/// </summary>
public static class TrayIcons
{
    public static Icon Connected => LogoHelper.CreateTrayIcon(GlassHelper.StatusGreen);
    public static Icon Recording => LogoHelper.CreateTrayIcon(GlassHelper.StatusGreen);
    public static Icon Idle => LogoHelper.CreateTrayIcon(GlassHelper.StatusYellow);
    public static Icon Disconnected => LogoHelper.CreateTrayIcon(GlassHelper.StatusRed);
    public static Icon Unknown => LogoHelper.CreateTrayIcon(GlassHelper.StatusGray);
    public static Icon Reconnecting => LogoHelper.CreateTrayIcon(GlassHelper.StatusOrange);
}
