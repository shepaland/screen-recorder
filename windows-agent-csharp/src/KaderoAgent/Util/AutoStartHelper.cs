using Microsoft.Win32;

namespace KaderoAgent.Util
{
    /// <summary>
    /// Manages auto-start on Windows login via HKCU\...\Run registry key.
    /// Launches the main agent process (which starts PipeServer + services,
    /// then spawns a --tray UI process automatically).
    /// </summary>
    public static class AutoStartHelper
    {
        private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
        private const string AppName = "KaderoAgent";

        public static void EnableAutoStart()
        {
            var exePath = System.Diagnostics.Process.GetCurrentProcess().MainModule?.FileName;
            if (exePath == null) return;
            using var key = Registry.CurrentUser.OpenSubKey(RunKey, true);
            // Launch main agent (not --tray). Agent will spawn tray process itself.
            key?.SetValue(AppName, $"\"{exePath}\"");
        }

        public static void DisableAutoStart()
        {
            using var key = Registry.CurrentUser.OpenSubKey(RunKey, true);
            key?.DeleteValue(AppName, false);
        }

        public static bool IsAutoStartEnabled()
        {
            using var key = Registry.CurrentUser.OpenSubKey(RunKey, false);
            return key?.GetValue(AppName) != null;
        }
    }
}
