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
    private readonly SemaphoreSlim _authLock = new(1, 1);
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

    /// <summary>
    /// Update server config from heartbeat device_settings and persist to CredentialStore.
    /// </summary>
    public void UpdateServerConfig(ServerConfig config)
    {
        _serverConfig = config;
        var creds = _credentialStore.Load();
        if (creds != null)
        {
            creds.ServerConfig = config;
            _credentialStore.Save(creds);
        }
    }

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

        // Try refresh (with re-auth fallback)
        return await RefreshTokenAsync();
    }

    public async Task<DeviceLoginResponse> RegisterAsync(string serverUrl, string registrationToken)
    {
        var hwId = HardwareId.Generate();
        var hostname = Environment.MachineName;
        var osVersion = Environment.OSVersion.ToString();

        var body = new
        {
            registration_token = registrationToken,
            device_info = new
            {
                hostname,
                os_version = osVersion,
                agent_version = GetType().Assembly.GetName().Version?.ToString() ?? "0.0.0",
                hardware_id = hwId
            }
        };

        var url = $"{serverUrl.TrimEnd('/')}/api/v1/auth/device-login";
        _logger.LogInformation("Registering device at {Url}", url);

        var response = await _apiClient.PostPublicAsync<DeviceLoginResponse>(url, body);
        if (response == null) throw new Exception("Empty response from server");

        _deviceId = response.DeviceId;
        _tokenStore.AccessToken = response.AccessToken;
        _tokenStore.RefreshToken = response.RefreshToken;
        _tokenStore.AccessTokenExpiry = DateTime.UtcNow.AddSeconds(response.ExpiresIn);
        // Mark that this config came from a real server response
        if (response.ServerConfig != null)
            response.ServerConfig.ConfigReceivedFromServer = true;
        _serverConfig = response.ServerConfig;
        SyncDeviceId();

        // Save credentials including registration token for future re-authentication
        _credentialStore.Save(new StoredCredentials
        {
            ServerUrl = serverUrl,
            DeviceId = response.DeviceId ?? "",
            RefreshToken = response.RefreshToken ?? "",
            AccessToken = response.AccessToken ?? "",
            RegistrationToken = registrationToken,
            ServerConfig = response.ServerConfig
        });

        _logger.LogInformation("Device registered: {DeviceId}", _deviceId);
        return response;
    }

    /// <summary>
    /// Validates a registration token against the server without performing device login.
    /// Returns token validity status, tenant name, and token name.
    /// </summary>
    public async Task<ValidateTokenResponse> ValidateTokenAsync(string serverUrl, string registrationToken)
    {
        var url = $"{serverUrl.TrimEnd('/')}/api/v1/auth/validate-registration-token";
        var body = new { registration_token = registrationToken };

        try
        {
            var response = await _apiClient.PostPublicAsync<ValidateTokenResponse>(url, body);
            return response ?? new ValidateTokenResponse { Valid = false, Reason = "Empty response from server" };
        }
        catch (HttpRequestException ex)
        {
            _logger.LogWarning(ex, "Token validation request failed");
            return new ValidateTokenResponse { Valid = false, Reason = $"Connection error: {ex.Message}" };
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Token validation failed");
            return new ValidateTokenResponse { Valid = false, Reason = ex.Message };
        }
    }

    /// <summary>
    /// Refreshes the access token using the stored refresh token.
    /// If refresh fails, falls back to re-authentication via device-login with stored registration token.
    /// Thread-safe: uses _authLock to prevent concurrent refresh race conditions that can
    /// trigger refresh-token-reuse detection and revoke all user tokens on the backend.
    /// </summary>
    public async Task<bool> RefreshTokenAsync()
    {
        // Acquire lock to prevent concurrent refresh calls (race condition → token revocation)
        if (!await _authLock.WaitAsync(TimeSpan.FromSeconds(30)))
        {
            _logger.LogWarning("Auth lock timeout — another refresh is in progress");
            return false;
        }

        try
        {
            // Double-check: token may have been refreshed by another caller while we waited
            if (!_tokenStore.IsAccessTokenExpired())
            {
                _logger.LogDebug("Token already refreshed by another caller");
                return true;
            }

            // Step 1: Try normal refresh via device-refresh endpoint
            var refreshed = await TryRefreshTokenInternalAsync();
            if (refreshed) return true;

            // Step 2: Refresh failed — try re-authentication via device-login
            _logger.LogWarning("Token refresh failed, attempting re-authentication via device-login...");
            return await TryReauthenticateAsync();
        }
        finally
        {
            _authLock.Release();
        }
    }

    /// <summary>
    /// Internal refresh — calls device-refresh endpoint using PostPublicAsync (no auth header, no 401 retry).
    /// </summary>
    private async Task<bool> TryRefreshTokenInternalAsync()
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

            // Use PostPublicAsync to avoid recursive 401-refresh and deadlocks
            var response = await _apiClient.PostPublicAsync<DeviceRefreshResponse>(url, body);
            if (response == null) return false;

            _tokenStore.AccessToken = response.AccessToken;
            _tokenStore.RefreshToken = response.RefreshToken;
            _tokenStore.AccessTokenExpiry = DateTime.UtcNow.AddSeconds(response.ExpiresIn);

            // Update stored credentials
            creds.AccessToken = response.AccessToken ?? "";
            creds.RefreshToken = response.RefreshToken ?? "";
            _credentialStore.Save(creds);

            _logger.LogDebug("Token refreshed successfully via device-refresh");
            return true;
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Token refresh via device-refresh failed");
            return false;
        }
    }

    /// <summary>
    /// Re-authenticate via device-login using stored registration token.
    /// This is the fallback when refresh token is expired/revoked.
    /// Uses PostPublicAsync (no auth header needed for device-login).
    /// </summary>
    private async Task<bool> TryReauthenticateAsync()
    {
        try
        {
            var creds = _credentialStore.Load();
            if (creds == null) return false;

            if (string.IsNullOrEmpty(creds.RegistrationToken))
            {
                _logger.LogWarning("No registration token stored — cannot re-authenticate. " +
                                   "Agent needs manual re-registration.");
                return false;
            }

            var hwId = HardwareId.Generate();
            var hostname = Environment.MachineName;
            var osVersion = Environment.OSVersion.ToString();

            var url = $"{creds.ServerUrl.TrimEnd('/')}/api/v1/auth/device-login";
            var body = new
            {
                registration_token = creds.RegistrationToken,
                device_info = new
                {
                    hostname,
                    os_version = osVersion,
                    agent_version = GetType().Assembly.GetName().Version?.ToString() ?? "0.0.0",
                    hardware_id = hwId
                }
            };

            _logger.LogInformation("Re-authenticating device via device-login at {Url}", url);
            var response = await _apiClient.PostPublicAsync<DeviceLoginResponse>(url, body);
            if (response == null)
            {
                _logger.LogError("Re-authentication failed: empty response");
                return false;
            }

            // Update tokens and device info
            _deviceId = response.DeviceId;
            _tokenStore.AccessToken = response.AccessToken;
            _tokenStore.RefreshToken = response.RefreshToken;
            _tokenStore.AccessTokenExpiry = DateTime.UtcNow.AddSeconds(response.ExpiresIn);
            // Mark incoming config as server-confirmed
            if (response.ServerConfig != null)
                response.ServerConfig.ConfigReceivedFromServer = true;
            _serverConfig = MergeServerConfigs(_serverConfig, response.ServerConfig);
            SyncDeviceId();

            // Persist updated credentials (preserve registration token)
            creds.DeviceId = response.DeviceId ?? creds.DeviceId;
            creds.RefreshToken = response.RefreshToken ?? "";
            creds.AccessToken = response.AccessToken ?? "";
            creds.ServerConfig = response.ServerConfig ?? creds.ServerConfig;
            _credentialStore.Save(creds);

            _logger.LogInformation("Re-authentication successful: device_id={DeviceId}", _deviceId);
            return true;
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Re-authentication via device-login failed");
            return false;
        }
    }

    /// <summary>
    /// Merge server configs during re-authentication: preserve admin-set recording parameters
    /// from the existing config, take connection-related settings from the incoming config.
    /// The next heartbeat device_settings will re-sync any stale values within 30 seconds.
    /// </summary>
    private ServerConfig MergeServerConfigs(ServerConfig? existing, ServerConfig? incoming)
    {
        if (incoming == null) return existing ?? new ServerConfig();
        if (existing == null) return incoming;

        // Always take connection-related settings from the new response
        var merged = new ServerConfig
        {
            IngestBaseUrl = incoming.IngestBaseUrl,
            ControlPlaneBaseUrl = incoming.ControlPlaneBaseUrl,
            // For recording settings: prefer existing admin overrides (set via heartbeat device_settings)
            // over server defaults from device-login. Next heartbeat will correct any stale values.
            CaptureFps = existing.CaptureFps > 0 ? existing.CaptureFps : incoming.CaptureFps,
            Quality = !string.IsNullOrEmpty(existing.Quality) ? existing.Quality : incoming.Quality,
            Resolution = !string.IsNullOrEmpty(existing.Resolution) ? existing.Resolution : incoming.Resolution,
            SegmentDurationSec = existing.SegmentDurationSec > 0 ? existing.SegmentDurationSec : incoming.SegmentDurationSec,
            HeartbeatIntervalSec = existing.HeartbeatIntervalSec > 0 ? existing.HeartbeatIntervalSec : incoming.HeartbeatIntervalSec,
            SessionMaxDurationHours = existing.SessionMaxDurationHours ?? incoming.SessionMaxDurationHours,
            SessionMaxDurationMin = existing.SessionMaxDurationMin ?? incoming.SessionMaxDurationMin,
            AutoStart = existing.AutoStart ?? incoming.AutoStart,
            RecordingEnabled = incoming.RecordingEnabled, // Always take server's latest value
            // If either config was received from server, the merged result is also server-confirmed
            ConfigReceivedFromServer = existing.ConfigReceivedFromServer || incoming.ConfigReceivedFromServer,
        };

        _logger.LogInformation(
            "ServerConfig merged on re-auth: fps={Fps} (was={OldFps}, server={NewFps}), quality={Quality}, configFromServer={FromServer}",
            merged.CaptureFps, existing.CaptureFps, incoming.CaptureFps, merged.Quality, merged.ConfigReceivedFromServer);

        return merged;
    }

    /// <summary>
    /// Ensures the access token is valid. If expired, refreshes (with re-auth fallback).
    /// Thread-safe via _authLock inside RefreshTokenAsync.
    /// </summary>
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

public class ValidateTokenResponse
{
    public bool Valid { get; set; }
    public string? TenantName { get; set; }
    public string? TokenName { get; set; }
    public string? Reason { get; set; }
}
