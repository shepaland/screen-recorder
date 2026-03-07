using System.Security.Principal;
using Microsoft.Extensions.Logging;

namespace KaderoAgent.Audit;

public class UserSessionInfo
{
    private readonly ILogger<UserSessionInfo> _logger;
    private string? _cachedUsername;

    public UserSessionInfo(ILogger<UserSessionInfo> logger)
    {
        _logger = logger;
    }

    /// <summary>
    /// Get current Windows username in DOMAIN\user format.
    /// Caches the result since the service user does not change during execution.
    /// </summary>
    public string GetCurrentUsername()
    {
        if (_cachedUsername != null) return _cachedUsername;

        // 1. Try WindowsIdentity (works when running under user context)
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

        // 2. Fallback: Environment.UserDomainName + UserName
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

        // 3. Last resort: "UNKNOWN"
        _cachedUsername = Environment.MachineName + "\\UNKNOWN";
        _logger.LogWarning("Could not determine username, using: {Username}", _cachedUsername);
        return _cachedUsername;
    }

    /// <summary>
    /// Invalidate cached username (e.g., on session logon/logoff).
    /// </summary>
    public void InvalidateCache() => _cachedUsername = null;
}
