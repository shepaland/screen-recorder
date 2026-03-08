using System.Runtime.InteropServices;
using System.Security.Principal;
using Microsoft.Extensions.Logging;

namespace KaderoAgent.Audit;

public class UserSessionInfo
{
    // ── WTS P/Invoke ───────────────────────────────────────────────────────────

    [DllImport("wtsapi32.dll", SetLastError = true)]
    private static extern bool WTSEnumerateSessions(
        IntPtr hServer, uint reserved, uint version,
        out IntPtr ppSessionInfo, out uint pCount);

    [DllImport("wtsapi32.dll", SetLastError = true)]
    private static extern bool WTSQuerySessionInformation(
        IntPtr hServer, uint sessionId, WtsInfoClass wtsInfoClass,
        out IntPtr ppBuffer, out uint pBytesReturned);

    [DllImport("wtsapi32.dll")]
    private static extern void WTSFreeMemory(IntPtr pMemory);

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Auto)]
    private struct WTS_SESSION_INFO
    {
        public uint SessionID;
        [MarshalAs(UnmanagedType.LPTStr)]
        public string? WinStationName;
        public int State; // WTS_CONNECTSTATE_CLASS
    }

    private enum WtsInfoClass
    {
        WTSUserName   = 5,
        WTSDomainName = 7,
    }

    // WTS_CONNECTSTATE_CLASS values
    private const int WTSActive      = 0;
    private const int WTSConnected   = 1;
    private const int WTSDisconnected = 4;

    // ── Fields ─────────────────────────────────────────────────────────────────

    private readonly ILogger<UserSessionInfo> _logger;
    private string? _cachedUsername;

    public UserSessionInfo(ILogger<UserSessionInfo> logger)
    {
        _logger = logger;
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /// <summary>
    /// Returns current Windows username in DOMAIN\user format.
    /// Works in interactive sessions and in Windows Service (Session 0 / SYSTEM).
    /// Call <see cref="InvalidateCache"/> on session logon/logoff.
    /// </summary>
    public string GetCurrentUsername()
    {
        if (_cachedUsername != null) return _cachedUsername;

        // 1. WindowsIdentity — works when running under a real user account
        try
        {
            var identity = WindowsIdentity.GetCurrent();
            if (!string.IsNullOrEmpty(identity.Name) && !IsSystemDomain(identity.Name))
            {
                _cachedUsername = identity.Name;
                _logger.LogInformation("Username from WindowsIdentity: {Username}", _cachedUsername);
                return _cachedUsername;
            }
        }
        catch (Exception ex)
        {
            _logger.LogDebug(ex, "WindowsIdentity lookup failed");
        }

        // 2. WTS session enumeration — works from Session 0 (Windows Service as SYSTEM).
        //    Enumerates all sessions and picks the first active real-user session.
        try
        {
            var wtsUsername = GetUsernameFromActiveSessions();
            if (wtsUsername != null)
            {
                _cachedUsername = wtsUsername;
                _logger.LogInformation("Username from WTS sessions: {Username}", _cachedUsername);
                return _cachedUsername;
            }
        }
        catch (Exception ex)
        {
            _logger.LogDebug(ex, "WTS session enumeration failed");
        }

        // 3. Environment fallback
        try
        {
            var domain = Environment.UserDomainName;
            var user   = Environment.UserName;
            if (!string.IsNullOrEmpty(user) && !IsSystemAccount(user))
            {
                _cachedUsername = $"{domain}\\{user}";
                _logger.LogInformation("Username from Environment: {Username}", _cachedUsername);
                return _cachedUsername;
            }
        }
        catch (Exception ex)
        {
            _logger.LogDebug(ex, "Environment username lookup failed");
        }

        // 4. Last resort — machine name, never null so FocusIntervalSink can start
        _cachedUsername = Environment.MachineName + "\\UNKNOWN";
        _logger.LogWarning("Could not determine real username, using: {Username}", _cachedUsername);
        return _cachedUsername;
    }

    /// <summary>Clears the cached username (call on session change).</summary>
    public void InvalidateCache() => _cachedUsername = null;

    // ── Private helpers ────────────────────────────────────────────────────────

    /// <summary>
    /// Enumerate WTS sessions on the local server and return the first real-user
    /// session that is Active or Connected (handles both console and RDP sessions).
    /// </summary>
    private string? GetUsernameFromActiveSessions()
    {
        if (!WTSEnumerateSessions(IntPtr.Zero, 0, 1, out var pSessions, out var count))
        {
            _logger.LogDebug("WTSEnumerateSessions failed: {Error}", Marshal.GetLastWin32Error());
            return null;
        }

        try
        {
            int structSize = Marshal.SizeOf<WTS_SESSION_INFO>();
            var ptr = pSessions;

            // Prefer Active sessions; fall back to Connected / Disconnected
            string? activeResult       = null;
            string? connectedResult    = null;
            string? disconnectedResult = null;

            for (int i = 0; i < count; i++, ptr = IntPtr.Add(ptr, structSize))
            {
                var info = Marshal.PtrToStructure<WTS_SESSION_INFO>(ptr);

                // Skip session 0 (Services session)
                if (info.SessionID == 0) continue;

                var username = GetUsernameForSession(info.SessionID);
                if (username == null) continue;

                switch (info.State)
                {
                    case WTSActive      when activeResult       == null: activeResult       = username; break;
                    case WTSConnected   when connectedResult    == null: connectedResult    = username; break;
                    case WTSDisconnected when disconnectedResult == null: disconnectedResult = username; break;
                }
            }

            return activeResult ?? connectedResult ?? disconnectedResult;
        }
        finally
        {
            WTSFreeMemory(pSessions);
        }
    }

    /// <summary>Query username + domain for a specific WTS session ID.</summary>
    private string? GetUsernameForSession(uint sessionId)
    {
        if (!WTSQuerySessionInformation(IntPtr.Zero, sessionId,
                WtsInfoClass.WTSUserName, out var userPtr, out _))
            return null;

        var username = Marshal.PtrToStringUni(userPtr) ?? "";
        WTSFreeMemory(userPtr);

        if (string.IsNullOrEmpty(username)) return null;

        if (!WTSQuerySessionInformation(IntPtr.Zero, sessionId,
                WtsInfoClass.WTSDomainName, out var domainPtr, out _))
        {
            // No domain — return bare username if it looks real
            return IsSystemAccount(username) ? null : username;
        }

        var domain = Marshal.PtrToStringUni(domainPtr) ?? "";
        WTSFreeMemory(domainPtr);

        // "NT AUTHORITY" covers SYSTEM, LOCAL SERVICE, NETWORK SERVICE
        // in ALL Windows locales (the authority name is always English).
        if (domain.Equals("NT AUTHORITY", StringComparison.OrdinalIgnoreCase))
            return null;

        return string.IsNullOrEmpty(domain) ? username : $"{domain}\\{username}";
    }

    /// <summary>True if the full "DOMAIN\user" string belongs to NT AUTHORITY.</summary>
    private static bool IsSystemDomain(string fullName) =>
        fullName.StartsWith("NT AUTHORITY\\", StringComparison.OrdinalIgnoreCase);

    /// <summary>True if the bare username looks like a built-in system account.</summary>
    private static bool IsSystemAccount(string username) =>
        username.Equals("SYSTEM",          StringComparison.OrdinalIgnoreCase) ||
        username.Equals("СИСТЕМА",         StringComparison.OrdinalIgnoreCase) ||
        username.Equals("LOCAL SERVICE",   StringComparison.OrdinalIgnoreCase) ||
        username.Equals("NETWORK SERVICE", StringComparison.OrdinalIgnoreCase);
}
