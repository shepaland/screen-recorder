using System.Runtime.InteropServices;
using System.Security.Principal;
using Microsoft.Extensions.Logging;

namespace KaderoAgent.Audit;

public class UserSessionInfo
{
    // WTS API for reading the active user session from Session 0 (Windows Service)
    [DllImport("wtsapi32.dll", SetLastError = true)]
    private static extern bool WTSQuerySessionInformation(
        IntPtr hServer, uint sessionId, WtsInfoClass wtsInfoClass,
        out IntPtr ppBuffer, out uint pBytesReturned);

    [DllImport("wtsapi32.dll")]
    private static extern void WTSFreeMemory(IntPtr pMemory);

    [DllImport("kernel32.dll")]
    private static extern uint WTSGetActiveConsoleSessionId();

    private enum WtsInfoClass
    {
        WTSUserName = 5,
        WTSDomainName = 7,
    }

    private readonly ILogger<UserSessionInfo> _logger;
    private string? _cachedUsername;

    public UserSessionInfo(ILogger<UserSessionInfo> logger)
    {
        _logger = logger;
    }

    /// <summary>
    /// Get current Windows username in DOMAIN\user format.
    /// Works both in interactive sessions and when running as a Windows Service (Session 0).
    /// Caches the result — call InvalidateCache() on session change.
    /// </summary>
    public string GetCurrentUsername()
    {
        if (_cachedUsername != null) return _cachedUsername;

        // 1. Try WindowsIdentity (works when running under user context, not SYSTEM)
        try
        {
            var identity = WindowsIdentity.GetCurrent();
            if (!string.IsNullOrEmpty(identity.Name) &&
                !identity.Name.Contains("SYSTEM", StringComparison.OrdinalIgnoreCase) &&
                !identity.Name.Contains("LOCAL SERVICE", StringComparison.OrdinalIgnoreCase) &&
                !identity.Name.Contains("NETWORK SERVICE", StringComparison.OrdinalIgnoreCase))
            {
                _cachedUsername = identity.Name;
                _logger.LogInformation("Username from WindowsIdentity: {Username}", _cachedUsername);
                return _cachedUsername;
            }
        }
        catch (Exception ex)
        {
            _logger.LogDebug(ex, "Failed to get username from WindowsIdentity");
        }

        // 2. Try WTS API — works when running as Windows Service in Session 0.
        //    Reads the username from the active console session.
        try
        {
            var wtsUsername = GetUsernameFromWts();
            if (wtsUsername != null)
            {
                _cachedUsername = wtsUsername;
                _logger.LogInformation("Username from WTS API: {Username}", _cachedUsername);
                return _cachedUsername;
            }
        }
        catch (Exception ex)
        {
            _logger.LogDebug(ex, "Failed to get username from WTS API");
        }

        // 3. Fallback: Environment.UserDomainName + UserName
        try
        {
            var domain = Environment.UserDomainName;
            var user = Environment.UserName;
            if (!string.IsNullOrEmpty(user) &&
                !user.Equals("SYSTEM", StringComparison.OrdinalIgnoreCase))
            {
                _cachedUsername = $"{domain}\\{user}";
                _logger.LogInformation("Username from Environment: {Username}", _cachedUsername);
                return _cachedUsername;
            }
        }
        catch (Exception ex)
        {
            _logger.LogDebug(ex, "Failed to get username from Environment");
        }

        // 4. Last resort
        _cachedUsername = Environment.MachineName + "\\UNKNOWN";
        _logger.LogWarning("Could not determine username, using: {Username}", _cachedUsername);
        return _cachedUsername;
    }

    /// <summary>
    /// Invalidate cached username (e.g., on session logon/logoff).
    /// </summary>
    public void InvalidateCache() => _cachedUsername = null;

    /// <summary>
    /// Query the logged-in username of the active console session via WTS API.
    /// Works from Session 0 (Windows Service running as SYSTEM).
    /// </summary>
    private string? GetUsernameFromWts()
    {
        var sessionId = WTSGetActiveConsoleSessionId();
        if (sessionId == 0xFFFFFFFF)
        {
            _logger.LogDebug("WTSGetActiveConsoleSessionId returned no active session");
            return null;
        }

        // Get username
        if (!WTSQuerySessionInformation(IntPtr.Zero, sessionId, WtsInfoClass.WTSUserName,
                out var userPtr, out _))
        {
            _logger.LogDebug("WTSQuerySessionInformation(WTSUserName) failed: error {Error}",
                Marshal.GetLastWin32Error());
            return null;
        }

        var username = Marshal.PtrToStringUni(userPtr) ?? "";
        WTSFreeMemory(userPtr);

        if (string.IsNullOrEmpty(username) ||
            username.Equals("SYSTEM", StringComparison.OrdinalIgnoreCase))
        {
            _logger.LogDebug("WTS session {SessionId} has no real user (got: '{Username}')", sessionId, username);
            return null;
        }

        // Get domain
        if (!WTSQuerySessionInformation(IntPtr.Zero, sessionId, WtsInfoClass.WTSDomainName,
                out var domainPtr, out _))
        {
            return username;
        }

        var domain = Marshal.PtrToStringUni(domainPtr) ?? "";
        WTSFreeMemory(domainPtr);

        return string.IsNullOrEmpty(domain) ? username : $"{domain}\\{username}";
    }
}
