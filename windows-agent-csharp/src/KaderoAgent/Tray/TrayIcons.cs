namespace KaderoAgent.Tray;

using System.Drawing;
using System.Drawing.Drawing2D;

/// <summary>
/// Generates tray icons programmatically (no embedded resources needed).
/// Green circle = recording+connected, Yellow = connected+idle, Red = disconnected, Gray = unknown.
/// </summary>
public static class TrayIcons
{
    public static Icon CreateIcon(Color color, bool recording = false)
    {
        using var bmp = new Bitmap(16, 16);
        using var g = Graphics.FromImage(bmp);
        g.SmoothingMode = SmoothingMode.AntiAlias;
        g.Clear(Color.Transparent);

        // Main circle
        using var brush = new SolidBrush(color);
        g.FillEllipse(brush, 1, 1, 14, 14);

        // Border
        using var pen = new Pen(Color.FromArgb(80, 0, 0, 0), 1);
        g.DrawEllipse(pen, 1, 1, 14, 14);

        // Recording indicator: small white circle in center
        if (recording)
        {
            using var whiteBrush = new SolidBrush(Color.White);
            g.FillEllipse(whiteBrush, 5, 5, 6, 6);
        }

        return Icon.FromHandle(bmp.GetHicon());
    }

    public static Icon Connected => CreateIcon(Color.FromArgb(76, 175, 80)); // Green
    public static Icon Recording => CreateIcon(Color.FromArgb(76, 175, 80), recording: true); // Green + dot
    public static Icon Idle => CreateIcon(Color.FromArgb(255, 193, 7)); // Yellow/Amber
    public static Icon Disconnected => CreateIcon(Color.FromArgb(244, 67, 54)); // Red
    public static Icon Unknown => CreateIcon(Color.FromArgb(158, 158, 158)); // Gray
    public static Icon Reconnecting => CreateIcon(Color.FromArgb(255, 152, 0)); // Orange
}
