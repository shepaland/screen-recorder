using System.Drawing;
using System.Drawing.Drawing2D;

namespace KaderoAgent.Resources
{
    /// <summary>
    /// Generates application icons programmatically: red "К" on dark background.
    /// No embedded .ico files needed.
    /// </summary>
    public static class LogoHelper
    {
        /// <summary>Creates application icon (red "К" on dark background).</summary>
        public static Icon CreateAppIcon(int size = 32)
        {
            using var bmp = new Bitmap(size, size);
            using var g = Graphics.FromImage(bmp);
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.TextRenderingHint = System.Drawing.Text.TextRenderingHint.AntiAliasGridFit;

            // Dark background
            using var bgBrush = new SolidBrush(Color.FromArgb(28, 28, 32));
            g.FillRectangle(bgBrush, 0, 0, size, size);

            // Red "К" (Tailwind red-600 #dc2626)
            using var font = new Font("Segoe UI", size * 0.6f, FontStyle.Bold);
            using var textBrush = new SolidBrush(Color.FromArgb(220, 38, 38));
            var sf = new StringFormat { Alignment = StringAlignment.Center, LineAlignment = StringAlignment.Center };
            g.DrawString("\u041A", font, textBrush, new RectangleF(0, 0, size, size), sf);

            return Icon.FromHandle(bmp.GetHicon());
        }

        /// <summary>Creates tray icon with status indicator dot in bottom-right corner.</summary>
        public static Icon CreateTrayIcon(Color statusColor, int size = 32)
        {
            using var bmp = new Bitmap(size, size);
            using var g = Graphics.FromImage(bmp);
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.TextRenderingHint = System.Drawing.Text.TextRenderingHint.AntiAliasGridFit;

            // Dark background
            using var bgBrush = new SolidBrush(Color.FromArgb(28, 28, 32));
            g.FillRectangle(bgBrush, 0, 0, size, size);

            // Red "К" (slightly smaller to leave room for status dot)
            using var font = new Font("Segoe UI", size * 0.5f, FontStyle.Bold);
            using var textBrush = new SolidBrush(Color.FromArgb(220, 38, 38));
            var sf = new StringFormat { Alignment = StringAlignment.Center, LineAlignment = StringAlignment.Center };
            g.DrawString("\u041A", font, textBrush, new RectangleF(0, -size * 0.05f, size, size), sf);

            // Status dot in bottom-right corner
            int dotSize = size / 4;
            using var dotBrush = new SolidBrush(statusColor);
            g.FillEllipse(dotBrush, size - dotSize - 1, size - dotSize - 1, dotSize, dotSize);

            return Icon.FromHandle(bmp.GetHicon());
        }

        /// <summary>Creates a logo bitmap for display in About dialog.</summary>
        public static Bitmap CreateLogoBitmap(int size = 64)
        {
            var bmp = new Bitmap(size, size);
            using var g = Graphics.FromImage(bmp);
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.TextRenderingHint = System.Drawing.Text.TextRenderingHint.AntiAliasGridFit;

            // Rounded dark background
            using var bgBrush = new SolidBrush(Color.FromArgb(28, 28, 32));
            using var path = CreateRoundedRect(new Rectangle(0, 0, size, size), size / 6);
            g.FillPath(bgBrush, path);

            // Red "К"
            using var font = new Font("Segoe UI", size * 0.55f, FontStyle.Bold);
            using var textBrush = new SolidBrush(Color.FromArgb(220, 38, 38));
            var sf = new StringFormat { Alignment = StringAlignment.Center, LineAlignment = StringAlignment.Center };
            g.DrawString("\u041A", font, textBrush, new RectangleF(0, 0, size, size), sf);

            return bmp;
        }

        private static GraphicsPath CreateRoundedRect(Rectangle rect, int radius)
        {
            var path = new GraphicsPath();
            var d = radius * 2;
            path.AddArc(rect.X, rect.Y, d, d, 180, 90);
            path.AddArc(rect.Right - d, rect.Y, d, d, 270, 90);
            path.AddArc(rect.Right - d, rect.Bottom - d, d, d, 0, 90);
            path.AddArc(rect.X, rect.Bottom - d, d, d, 90, 90);
            path.CloseFigure();
            return path;
        }
    }
}
