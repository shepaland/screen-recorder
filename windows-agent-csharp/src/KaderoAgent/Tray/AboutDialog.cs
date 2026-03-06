namespace KaderoAgent.Tray;

using System.Windows.Forms;
using System.Drawing;
using KaderoAgent.Resources;

/// <summary>
/// About dialog with glass/acrylic UI style.
/// Shows logo, version and description.
/// </summary>
public class AboutDialog : Form
{
    public AboutDialog()
    {
        SetStyle(ControlStyles.SupportsTransparentBackColor | ControlStyles.OptimizedDoubleBuffer, true);

        Text = "О программе";
        Size = new Size(350, 220);
        MaximizeBox = false;
        MinimizeBox = false;
        ShowInTaskbar = false;
        Icon = LogoHelper.CreateAppIcon(32);

        // Glass effect
        GlassHelper.ApplyGlassEffect(this);
        GlassHelper.PositionCenter(this);

        // Paint border
        Paint += (_, e) => GlassHelper.PaintGlassBorder(this, e);

        // Title bar
        var titleBar = GlassHelper.CreateTitleBar(this, "О программе", Width);
        Controls.Add(titleBar);
        GlassHelper.EnableDrag(this);

        // Logo
        var logoPicture = new PictureBox
        {
            Image = LogoHelper.CreateLogoBitmap(48),
            Size = new Size(48, 48),
            Location = new Point(24, 50),
            SizeMode = PictureBoxSizeMode.Zoom,
            BackColor = Color.Transparent
        };
        Controls.Add(logoPicture);

        // Title next to logo
        var titleLabel = new Label
        {
            Text = "Кадеро Agent",
            Location = new Point(80, 54),
            AutoSize = true,
            Font = new Font("Segoe UI", 16, FontStyle.Bold),
            ForeColor = GlassHelper.AccentColor,
            BackColor = Color.Transparent
        };
        Controls.Add(titleLabel);

        var versionLabel = new Label
        {
            Text = $"Версия {GetType().Assembly.GetName().Version?.ToString() ?? "1.0.0"}",
            Location = new Point(80, 85),
            AutoSize = true,
            Font = new Font("Segoe UI", 9.5f),
            ForeColor = GlassHelper.TextSecondary,
            BackColor = Color.Transparent
        };
        Controls.Add(versionLabel);

        var descLabel = new Label
        {
            Text = "Агент записи экрана для системы Кадеро.\nСлужба работает в фоновом режиме.",
            Location = new Point(24, 112),
            Size = new Size(300, 40),
            Font = new Font("Segoe UI", 9),
            ForeColor = GlassHelper.TextPrimary,
            BackColor = Color.Transparent
        };
        Controls.Add(descLabel);

        var closeBtn = GlassHelper.CreateAccentButton("Закрыть", (Width - 120) / 2, 162, 120, 32);
        closeBtn.Click += (_, _) => Close();
        Controls.Add(closeBtn);
        AcceptButton = closeBtn;
    }
}
