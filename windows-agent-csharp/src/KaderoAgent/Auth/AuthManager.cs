using System.Text.Json;
using KaderoAgent.Configuration;
using KaderoAgent.Util;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;

namespace KaderoAgent.Auth;

public class AuthManager
{
    private readonly CredentialStore _credentialStore;
    private readonly TokenStore _tokenStore;
    private readonly ApiClient _apiClient;
    private readonly IOptions<AgentConfig> _config;
    private readonly ILogger<AuthManager> _logger;
    private ServerConfig? _serverConfig;
    private string? _deviceId;

    private void SyncDeviceId()
    {
        if (!string.IsNullOrEmpty(_deviceId))
            _apiClient.SetDeviceId(_deviceId);
    }

    public ServerConfig? ServerConfig => _serverConfig;
    public string? DeviceId => _deviceId;
    public bool IsAuthenticated => !string.IsNullOrEmpty(_tokenStore.AccessToken);

    public AuthManager(CredentialStore credentialStore, TokenStore tokenStore,
        ApiClient apiClient, IOptions<AgentConfig> config, ILogger<AuthManager> logger)
    {
        _credentialStore = credentialStore;
        _tokenStore = tokenStore;
        _apiClient = apiClient;
        _config = config;
        _logger = logger;
    }

    public async Task<bool> InitializeAsync()
    {
        var creds = _credentialStore.Load();
        if (creds == null) return false;

        _deviceId = creds.DeviceId;
        _serverConfig = creds.ServerConfig;
        _tokenStore.RefreshToken = creds.RefreshToken;
        _tokenStore.AccessToken = creds.AccessToken;
        SyncDeviceId();

        // Try refresh
        return await RefreshTokenAsync();
    }

    public async Task<DeviceLoginResponse> RegisterAsync(string serverUrl, string registrationToken,
        string? username = null, string? password = null)
    {
        var hwId = HardwareId.Generate();
        var hostname = Environment.MachineName;
        var osVersion = Environment.OSVersion.ToString();

        var body = new
        {
            username = username ?? "",
            password = password ?? "",
            registration_token = registrationToken,
            device_info = new
            {
                hostname,
                os_version = osVersion,
                agent_version = "1.0.0",
                hardware_id = hwId
            }
        };

        var url = $"{serverUrl.TrimEnd('/')}/api/v1/auth/device-login";
        _logger.LogInformation("Registering device at {Url}", url);

        var response = await _apiClient.PostAsync<DeviceLoginResponse>(url, body);
        if (response == null) throw new Exception("Empty response from server");

        _deviceId = response.DeviceId;
        _tokenStore.AccessToken = response.AccessToken;
        _tokenStore.RefreshToken = response.RefreshToken;
        _tokenStore.AccessTokenExpiry = DateTime.UtcNow.AddSeconds(response.ExpiresIn);
        _serverConfig = response.ServerConfig;
        SyncDeviceId();

        // Save credentials
        _credentialStore.Save(new StoredCredentials
        {
            ServerUrl = serverUrl,
            DeviceId = response.DeviceId ?? "",
            RefreshToken = response.RefreshToken ?? "",
            AccessToken = response.AccessToken ?? "",
            ServerConfig = response.ServerConfig
        });

        _logger.LogInformation("Device registered: {DeviceId}", _deviceId);
        return response;
    }

    public async Task<bool> RefreshTokenAsync()
    {
        try
        {
            var creds = _credentialStore.Load();
            if (creds == null || string.IsNullOrEmpty(_tokenStore.RefreshToken)) return false;

            var url = $"{creds.ServerUrl.TrimEnd('/')}/api/v1/auth/device-refresh";
            var body = new
            {
                refresh_token = _tokenStore.RefreshToken,
                device_id = _deviceId
            };

            var response = await _apiClient.PostAsync<DeviceRefreshResponse>(url, body);
            if (response == null) return false;

            _tokenStore.AccessToken = response.AccessToken;
            _tokenStore.RefreshToken = response.RefreshToken;
            _tokenStore.AccessTokenExpiry = DateTime.UtcNow.AddSeconds(response.ExpiresIn);

            // Update stored credentials
            creds.AccessToken = response.AccessToken ?? "";
            creds.RefreshToken = response.RefreshToken ?? "";
            _credentialStore.Save(creds);

            _logger.LogDebug("Token refreshed successfully");
            return true;
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Token refresh failed");
            return false;
        }
    }

    public async Task EnsureValidTokenAsync()
    {
        if (_tokenStore.IsAccessTokenExpired())
        {
            await RefreshTokenAsync();
        }
    }
}

public class DeviceLoginResponse
{
    public string? AccessToken { get; set; }
    public string? RefreshToken { get; set; }
    public string? TokenType { get; set; }
    public long ExpiresIn { get; set; }
    public string? DeviceId { get; set; }
    public string? DeviceStatus { get; set; }
    public ServerConfig? ServerConfig { get; set; }
}

public class DeviceRefreshResponse
{
    public string? AccessToken { get; set; }
    public string? RefreshToken { get; set; }
    public string? TokenType { get; set; }
    public long ExpiresIn { get; set; }
}
