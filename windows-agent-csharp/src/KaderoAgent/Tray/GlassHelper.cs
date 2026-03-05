namespace KaderoAgent.Tray;

using System.Drawing;
using System.Drawing.Drawing2D;
using System.Runtime.InteropServices;
using System.Windows.Forms;

/// <summary>
/// Helper for applying glass/acrylic blur effect and rounded corners on Windows 10/11.
/// Uses DWM (Desktop Window Manager) and SetWindowCompositionAttribute APIs.
/// </summary>
public static class GlassHelper
{
    // ── Colors (dark glass theme with Alfa-Bank accent) ──
    public static readonly Color BackgroundColor = Color.FromArgb(200, 28, 28, 32);
    public static readonly Color AccentColor = Color.FromArgb(229, 57, 53);       // #E53935
    public static readonly Color AccentDark = Color.FromArgb(183, 28, 28);         // #B71C1C
    public static readonly Color TextPrimary = Color.White;
    public static readonly Color TextSecondary = Color.FromArgb(180, 255, 255, 255);
    public static readonly Color InputBackground = Color.FromArgb(60, 255, 255, 255);
    public static readonly Color InputBorder = Color.FromArgb(80, 255, 255, 255);
    public static readonly Color BorderColor = Color.FromArgb(60, 255, 255, 255);
    public static readonly Color SeparatorColor = Color.FromArgb(40, 255, 255, 255);
    public static readonly Color StatusGreen = Color.FromArgb(76, 175, 80);
    public static readonly Color StatusOrange = Color.FromArgb(255, 152, 0);
    public static readonly Color StatusRed = Color.FromArgb(244, 67, 54);
    public static readonly Color StatusYellow = Color.FromArgb(255, 193, 7);
    public static readonly Color StatusGray = Color.FromArgb(158, 158, 158);

    // ── DWM APIs ──

    [DllImport("dwmapi.dll", PreserveSig = true)]
    private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int attrValue, int attrSize);

    private const int DWMWA_WINDOW_CORNER_PREFERENCE = 33;
    private const int DWMWCP_ROUND = 2;         // Rounded corners (Win11)
    private const int DWMWCP_ROUNDSMALL = 3;     // Small rounded corners

    // ── Composition (Acrylic blur) ──

    [DllImport("user32.dll")]
    private static extern int SetWindowCompositionAttribute(IntPtr hwnd, ref WindowCompositionAttribData data);

    [StructLayout(LayoutKind.Sequential)]
    private struct WindowCompositionAttribData
    {
        public int Attribute;
        public IntPtr Data;
        public int SizeOfData;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct AccentPolicy
    {
        public int AccentState;
        public int AccentFlags;
        public int GradientColor;
        public int AnimationId;
    }

    private const int WCA_ACCENT_POLICY = 19;
    private const int ACCENT_ENABLE_ACRYLICBLURBEHIND = 4;
    private const int ACCENT_ENABLE_BLURBEHIND = 3;

    // ── Region for rounded corners (fallback) ──

    [DllImport("gdi32.dll")]
    private static extern IntPtr CreateRoundRectRgn(int x1, int y1, int x2, int y2, int cx, int cy);

    /// <summary>
    /// Apply glass effect to a form: acrylic blur + rounded corners.
    /// </summary>
    public static void ApplyGlassEffect(Form form)
    {
        form.FormBorderStyle = FormBorderStyle.None;
        form.BackColor = Color.FromArgb(28, 28, 32);

        try
        {
            // Try Win11 rounded corners via DWM
            int cornerPref = DWMWCP_ROUND;
            DwmSetWindowAttribute(form.Handle, DWMWA_WINDOW_CORNER_PREFERENCE, ref cornerPref, sizeof(int));
        }
        catch
        {
            // Fallback: use region for rounded corners
            ApplyRoundedRegion(form, 12);
        }

        try
        {
            EnableAcrylic(form.Handle, BackgroundColor);
        }
        catch
        {
            // Fallback: just use semi-transparent background via double buffering
            form.BackColor = Color.FromArgb(28, 28, 32);
        }
    }

    private static void EnableAcrylic(IntPtr hwnd, Color blendColor)
    {
        var accent = new AccentPolicy
        {
            AccentState = ACCENT_ENABLE_ACRYLICBLURBEHIND,
            AccentFlags = 2, // ACCENT_FLAG_DRAW_ALL
            GradientColor = (blendColor.A << 24) | (blendColor.B << 16) | (blendColor.G << 8) | blendColor.R
        };

        var accentPtr = Marshal.AllocHGlobal(Marshal.SizeOf(accent));
        try
        {
            Marshal.StructureToPtr(accent, accentPtr, false);
            var data = new WindowCompositionAttribData
            {
                Attribute = WCA_ACCENT_POLICY,
                Data = accentPtr,
                SizeOfData = Marshal.SizeOf(accent)
            };
            SetWindowCompositionAttribute(hwnd, ref data);
        }
        finally
        {
            Marshal.FreeHGlobal(accentPtr);
        }
    }

    public static void ApplyRoundedRegion(Form form, int radius)
    {
        var rgn = CreateRoundRectRgn(0, 0, form.Width + 1, form.Height + 1, radius, radius);
        form.Region = Region.FromHrgn(rgn);
    }

    /// <summary>
    /// Position form at bottom-right of working area (above taskbar).
    /// </summary>
    public static void PositionBottomRight(Form form, int margin = 12)
    {
        var workArea = Screen.PrimaryScreen!.WorkingArea;
        form.StartPosition = FormStartPosition.Manual;
        form.Location = new Point(
            workArea.Right - form.Width - margin,
            workArea.Bottom - form.Height - margin
        );
    }

    /// <summary>
    /// Position form at center of working area.
    /// </summary>
    public static void PositionCenter(Form form)
    {
        var workArea = Screen.PrimaryScreen!.WorkingArea;
        form.StartPosition = FormStartPosition.Manual;
        form.Location = new Point(
            workArea.Left + (workArea.Width - form.Width) / 2,
            workArea.Top + (workArea.Height - form.Height) / 2
        );
    }

    // ── Dragging support ──

    private static bool _dragging;
    private static Point _dragStart;

    public static void EnableDrag(Form form)
    {
        form.MouseDown += (_, e) =>
        {
            if (e.Button == MouseButtons.Left)
            {
                _dragging = true;
                _dragStart = e.Location;
            }
        };
        form.MouseMove += (_, e) =>
        {
            if (_dragging)
                form.Location = new Point(
                    form.Location.X + e.X - _dragStart.X,
                    form.Location.Y + e.Y - _dragStart.Y);
        };
        form.MouseUp += (_, e) =>
        {
            if (e.Button == MouseButtons.Left)
                _dragging = false;
        };
    }

    /// <summary>
    /// Enable drag on a specific control (e.g., title bar panel).
    /// </summary>
    public static void EnableDragOn(Control control, Form form)
    {
        bool dragging = false;
        Point start = Point.Empty;

        control.MouseDown += (_, e) =>
        {
            if (e.Button == MouseButtons.Left) { dragging = true; start = e.Location; }
        };
        control.MouseMove += (_, e) =>
        {
            if (dragging)
                form.Location = new Point(
                    form.Location.X + e.X - start.X,
                    form.Location.Y + e.Y - start.Y);
        };
        control.MouseUp += (_, e) =>
        {
            if (e.Button == MouseButtons.Left) dragging = false;
        };
    }

    // ── Styled control factories ──

    /// <summary>
    /// Create a custom title bar panel with title text and close button.
    /// </summary>
    public static Panel CreateTitleBar(Form form, string title, int width)
    {
        var bar = new Panel
        {
            Size = new Size(width, 40),
            Location = new Point(0, 0),
            BackColor = Color.Transparent
        };

        var titleLabel = new Label
        {
            Text = title,
            ForeColor = TextPrimary,
            Font = new Font("Segoe UI", 11, FontStyle.Bold),
            AutoSize = false,
            Size = new Size(width - 50, 30),
            Location = new Point(16, 8),
            BackColor = Color.Transparent
        };
        bar.Controls.Add(titleLabel);
        EnableDragOn(titleLabel, form);

        var closeBtn = new Label
        {
            Text = "\u2715",
            ForeColor = TextSecondary,
            Font = new Font("Segoe UI", 12, FontStyle.Regular),
            Size = new Size(30, 30),
            Location = new Point(width - 38, 5),
            TextAlign = ContentAlignment.MiddleCenter,
            Cursor = Cursors.Hand,
            BackColor = Color.Transparent
        };
        closeBtn.MouseEnter += (_, _) => closeBtn.ForeColor = AccentColor;
        closeBtn.MouseLeave += (_, _) => closeBtn.ForeColor = TextSecondary;
        closeBtn.Click += (_, _) => form.Close();
        bar.Controls.Add(closeBtn);

        EnableDragOn(bar, form);
        return bar;
    }

    /// <summary>
    /// Create a section header label.
    /// </summary>
    public static Label CreateSectionHeader(string text, int x, int y)
    {
        return new Label
        {
            Text = text,
            Location = new Point(x, y),
            AutoSize = true,
            Font = new Font("Segoe UI", 10, FontStyle.Bold),
            ForeColor = AccentColor,
            BackColor = Color.Transparent
        };
    }

    /// <summary>
    /// Create a separator line.
    /// </summary>
    public static Panel CreateSeparator(int x, int y, int width)
    {
        return new Panel
        {
            Location = new Point(x, y),
            Size = new Size(width, 1),
            BackColor = SeparatorColor
        };
    }

    /// <summary>
    /// Create a styled text label.
    /// </summary>
    public static Label CreateLabel(string text, int x, int y, int width, bool bold = false, bool secondary = false)
    {
        return new Label
        {
            Text = text,
            Location = new Point(x, y),
            Size = new Size(width, 20),
            Font = new Font("Segoe UI", 9, bold ? FontStyle.Bold : FontStyle.Regular),
            ForeColor = secondary ? TextSecondary : TextPrimary,
            BackColor = Color.Transparent
        };
    }

    /// <summary>
    /// Create a styled text box with glass appearance.
    /// </summary>
    public static TextBox CreateTextBox(int x, int y, int width, string? placeholder = null, bool password = false)
    {
        var tb = new TextBox
        {
            Location = new Point(x, y),
            Size = new Size(width, 28),
            BackColor = Color.FromArgb(45, 45, 50),
            ForeColor = TextPrimary,
            BorderStyle = BorderStyle.FixedSingle,
            Font = new Font("Segoe UI", 9.5f)
        };
        if (placeholder != null) tb.PlaceholderText = placeholder;
        if (password) tb.UseSystemPasswordChar = true;
        return tb;
    }

    /// <summary>
    /// Create a styled accent button.
    /// </summary>
    public static Button CreateAccentButton(string text, int x, int y, int width = 160, int height = 35)
    {
        var btn = new Button
        {
            Text = text,
            Location = new Point(x, y),
            Size = new Size(width, height),
            BackColor = AccentColor,
            ForeColor = Color.White,
            FlatStyle = FlatStyle.Flat,
            Cursor = Cursors.Hand,
            Font = new Font("Segoe UI", 9.5f, FontStyle.Bold)
        };
        btn.FlatAppearance.BorderSize = 0;
        btn.FlatAppearance.MouseOverBackColor = Color.FromArgb(239, 83, 80);  // lighter red
        btn.FlatAppearance.MouseDownBackColor = AccentDark;
        return btn;
    }

    /// <summary>
    /// Create a status indicator (colored circle).
    /// </summary>
    public static Panel CreateIndicator(int x, int y, Color color)
    {
        var panel = new Panel
        {
            Location = new Point(x, y + 2),
            Size = new Size(12, 12),
            BackColor = color
        };
        panel.Paint += (_, e) =>
        {
            e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
            using var brush = new SolidBrush(panel.BackColor);
            e.Graphics.FillEllipse(brush, 0, 0, 11, 11);
            // Glow effect
            using var glowBrush = new SolidBrush(Color.FromArgb(60, panel.BackColor));
            e.Graphics.FillEllipse(glowBrush, -2, -2, 15, 15);
        };
        return panel;
    }

    /// <summary>
    /// Paint glass border on form.
    /// </summary>
    public static void PaintGlassBorder(Form form, PaintEventArgs e)
    {
        using var pen = new Pen(BorderColor, 1);
        var rect = new Rectangle(0, 0, form.Width - 1, form.Height - 1);

        // Draw rounded rectangle border
        using var path = CreateRoundedRectPath(rect, 12);
        e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
        e.Graphics.DrawPath(pen, path);
    }

    private static GraphicsPath CreateRoundedRectPath(Rectangle rect, int radius)
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
