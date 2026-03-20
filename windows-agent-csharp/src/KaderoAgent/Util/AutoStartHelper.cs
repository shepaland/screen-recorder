using Microsoft.Win32;

namespace KaderoAgent.Util
{
    /// <summary>
    /// Manages auto-start on Windows login via HKCU\...\Run registry key.
    /// Launches --headless (analytics collector) from HKCU\Run.
    /// The Windows Service is managed by sc.exe. Tray UI is optional (Start Menu).
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
            // Launch --headless (analytics collector) from HKCU\Run.
            // This ensures focus intervals and input events are always collected.
            key?.SetValue(AppName, $"\"{exePath}\" --headless");
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
