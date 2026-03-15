using System.Windows.Automation;

namespace KaderoAgent.Tray;

/// <summary>
/// Resolves UI element info at screen coordinates using UI Automation API.
/// Called async after each mouse click with 200ms timeout.
/// For Edit elements (input fields), element name is redacted to "[input]" for privacy.
/// </summary>
public static class UIElementResolver
{
    private const int TimeoutMs = 200;

    public record ElementInfo(string ElementType, string? ElementName);

    /// <summary>
    /// Get UI element at (x, y) screen coordinates. Returns null on timeout or failure.
    /// </summary>
    public static ElementInfo? GetElementAt(int x, int y)
    {
        try
        {
            using var cts = new CancellationTokenSource(TimeoutMs);
            var task = Task.Run(() => GetElementCore(x, y), cts.Token);
            if (task.Wait(TimeoutMs))
                return task.Result;
            return null; // timeout
        }
        catch
        {
            return null;
        }
    }

    private static ElementInfo? GetElementCore(int x, int y)
    {
        try
        {
            var point = new System.Windows.Point(x, y);
            var element = AutomationElement.FromPoint(point);
            if (element == null) return null;

            var controlType = element.Current.ControlType;
            var typeName = MapControlType(controlType);
            var name = element.Current.Name;

            // Privacy: redact text content of input fields
            if (typeName == "Edit" || typeName == "Document")
                name = "[input]";

            // Truncate long names
            if (name?.Length > 200)
                name = name[..200];

            return new ElementInfo(typeName, string.IsNullOrEmpty(name) ? null : name);
        }
        catch
        {
            return null;
        }
    }

    private static string MapControlType(ControlType? ct)
    {
        if (ct == null) return "Unknown";
        if (ct == ControlType.Button) return "Button";
        if (ct == ControlType.Edit) return "Edit";
        if (ct == ControlType.ComboBox) return "ComboBox";
        if (ct == ControlType.Hyperlink) return "Link";
        if (ct == ControlType.MenuItem) return "MenuItem";
        if (ct == ControlType.TreeItem) return "TreeItem";
        if (ct == ControlType.ListItem) return "ListItem";
        if (ct == ControlType.TabItem) return "Tab";
        if (ct == ControlType.CheckBox) return "CheckBox";
        if (ct == ControlType.RadioButton) return "RadioButton";
        if (ct == ControlType.Text) return "Text";
        if (ct == ControlType.Image) return "Image";
        if (ct == ControlType.Document) return "Document";
        if (ct == ControlType.DataItem) return "DataItem";
        if (ct == ControlType.ScrollBar) return "ScrollBar";
        if (ct == ControlType.Slider) return "Slider";
        if (ct == ControlType.ToolBar) return "ToolBar";
        if (ct == ControlType.StatusBar) return "StatusBar";
        if (ct == ControlType.Menu) return "Menu";
        if (ct == ControlType.Window) return "Window";
        if (ct == ControlType.Pane) return "Pane";
        return "Unknown";
    }
}
