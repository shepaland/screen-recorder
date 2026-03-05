namespace KaderoAgent.Tray;

using System.Windows.Forms;
using System.Drawing;

public class AboutDialog : Form
{
    public AboutDialog()
    {
        Text = "О программе";
        Size = new Size(350, 200);
        StartPosition = FormStartPosition.CenterScreen;
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        MinimizeBox = false;
        ShowInTaskbar = false;

        var titleLabel = new Label
        {
            Text = "Кадеро Agent",
            Location = new Point(20, 20),
            AutoSize = true,
            Font = new Font(Font.FontFamily, 14, FontStyle.Bold),
            ForeColor = Color.FromArgb(183, 28, 28)
        };
        Controls.Add(titleLabel);

        var versionLabel = new Label
        {
            Text = $"Версия: {GetType().Assembly.GetName().Version?.ToString() ?? "1.0.0"}",
            Location = new Point(20, 55),
            AutoSize = true
        };
        Controls.Add(versionLabel);

        var descLabel = new Label
        {
            Text = "Агент записи экрана для системы Кадеро.\nСлужба работает в фоновом режиме.",
            Location = new Point(20, 80),
            Size = new Size(300, 40)
        };
        Controls.Add(descLabel);

        var closeBtn = new Button
        {
            Text = "Закрыть",
            Location = new Point(125, 125),
            Size = new Size(100, 30),
            DialogResult = DialogResult.OK
        };
        Controls.Add(closeBtn);
        AcceptButton = closeBtn;
    }
}
