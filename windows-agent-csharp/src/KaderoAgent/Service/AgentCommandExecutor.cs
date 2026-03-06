namespace KaderoAgent.Service;

using KaderoAgent.Auth;
using KaderoAgent.Ipc;
using Microsoft.Extensions.Logging;

public class AgentCommandExecutor : ICommandExecutor
{
    private readonly AuthManager _authManager;
    private readonly CredentialStore _credentialStore;
    private readonly AgentStatusProvider _statusProvider;
    private readonly ILogger<AgentCommandExecutor> _logger;

    public AgentCommandExecutor(AuthManager authManager, CredentialStore credentialStore,
        AgentStatusProvider statusProvider, ILogger<AgentCommandExecutor> logger)
    {
        _authManager = authManager;
        _credentialStore = credentialStore;
        _statusProvider = statusProvider;
        _logger = logger;
    }

    public async Task<bool> ReconnectAsync(string? newServerUrl, string? newToken, CancellationToken ct)
    {
        try
        {
            _statusProvider.SetConnectionStatus("reconnecting");
            _logger.LogInformation("Reconnecting with new settings. ServerUrl={Url}", newServerUrl ?? "(unchanged)");

            if (!string.IsNullOrEmpty(newServerUrl) && !string.IsNullOrEmpty(newToken))
            {
                // New registration with new server URL and token
                _credentialStore.Clear();
                var response = await _authManager.RegisterAsync(newServerUrl, newToken);
                _credentialStore.ClearPendingRegistration();
                _statusProvider.SetConnectionStatus("connected");
                _statusProvider.SetLastError(null);
                _logger.LogInformation("Reconnected with new credentials. DeviceId={DeviceId}", response.DeviceId);
                return true;
            }
            else
            {
                // Just refresh token with existing credentials
                var result = await _authManager.RefreshTokenAsync();
                _statusProvider.SetConnectionStatus(result ? "connected" : "error");
                return result;
            }
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Reconnect failed");
            _statusProvider.SetConnectionStatus("error");
            _statusProvider.SetLastError(ex.Message);
            return false;
        }
    }
}
