package com.prg.agent.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.prg.agent.config.AgentConfig;
import com.prg.agent.config.ServerConfig;
import com.prg.agent.util.HardwareId;
import com.prg.agent.util.HttpClient;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;

/**
 * Manages device authentication lifecycle: login, token refresh, credential persistence.
 *
 * <p>Handles:
 * <ul>
 *   <li>Initial device login with registration token + user credentials</li>
 *   <li>Automatic access token refresh when expiring within 2 minutes</li>
 *   <li>Encrypted persistence of credentials via CredentialStore</li>
 *   <li>Recovery of session after agent restart</li>
 * </ul>
 */
public class AuthManager {

    private static final Logger log = LoggerFactory.getLogger(AuthManager.class);
    private static final int TOKEN_REFRESH_THRESHOLD_SECONDS = 120; // Refresh 2 min before expiry

    private final AgentConfig config;
    private final HttpClient httpClient;
    private final CredentialStore credentialStore;
    private final TokenStore tokenStore;

    @Getter
    private volatile ServerConfig serverConfig;
    @Getter
    private volatile String tenantName;
    private volatile boolean refreshInProgress = false;

    public AuthManager(AgentConfig config, HttpClient httpClient, CredentialStore credentialStore) {
        this.config = config;
        this.httpClient = httpClient;
        this.credentialStore = credentialStore;
        this.tokenStore = new TokenStore();
    }

    /**
     * Performs device login with registration token and user credentials.
     *
     * @param registrationToken the organization registration token
     * @param username          the admin/manager username
     * @param password          the user password
     * @return the login response containing tokens and device info
     * @throws AuthException if authentication fails
     */
    public DeviceLoginResponse login(String registrationToken, String username, String password) throws AuthException {
        log.info("Attempting device login for user '{}' at {}", username, config.getServerAuthUrl());

        DeviceLoginRequest request = new DeviceLoginRequest();
        request.setUsername(username);
        request.setPassword(password);
        request.setRegistrationToken(registrationToken);

        DeviceLoginRequest.DeviceInfo deviceInfo = new DeviceLoginRequest.DeviceInfo();
        deviceInfo.setHostname(HardwareId.getHostname());
        deviceInfo.setOsVersion(System.getProperty("os.name") + " " + System.getProperty("os.version"));
        deviceInfo.setAgentVersion("1.0.0");
        deviceInfo.setHardwareId(HardwareId.getHardwareId());
        request.setDeviceInfo(deviceInfo);

        try {
            String loginUrl = config.getServerAuthUrl() + "/device-login";
            DeviceLoginResponse response = httpClient.post(loginUrl, request, DeviceLoginResponse.class);

            if (response == null) {
                throw new AuthException("Empty response from server");
            }

            // Store tokens
            tokenStore.updateTokens(
                    response.getAccessToken(),
                    response.getRefreshToken(),
                    response.getExpiresIn(),
                    response.getDeviceId()
            );

            // Store server config
            this.serverConfig = response.getServerConfig();
            this.tenantName = extractTenantName(response);

            // Apply server config to agent config
            if (serverConfig != null) {
                config.applyServerConfig(serverConfig);
            }

            // Persist credentials
            saveCredentials(response);

            log.info("Device login successful. Device ID: {}, User: {}",
                    response.getDeviceId(), username);

            return response;
        } catch (HttpClient.HttpException e) {
            String message = parseErrorMessage(e);
            log.error("Device login failed: {} (HTTP {})", message, e.getStatusCode());
            throw new AuthException(message, e);
        } catch (Exception e) {
            if (e instanceof AuthException) throw (AuthException) e;
            log.error("Device login failed with unexpected error", e);
            throw new AuthException("Login failed: " + e.getMessage(), e);
        }
    }

    /**
     * Refreshes the access token using the stored refresh token.
     * Thread-safe - prevents concurrent refresh attempts.
     */
    public synchronized void refreshTokenIfNeeded() throws AuthException {
        if (!tokenStore.isAccessTokenExpiringSoon(TOKEN_REFRESH_THRESHOLD_SECONDS)) {
            return; // Token is still valid
        }

        if (refreshInProgress) {
            return; // Another thread is already refreshing
        }

        String currentRefreshToken = tokenStore.getRefreshToken();
        if (currentRefreshToken == null) {
            throw new AuthException("No refresh token available");
        }

        refreshInProgress = true;
        try {
            log.debug("Refreshing access token...");

            DeviceRefreshRequest request = new DeviceRefreshRequest();
            request.setRefreshToken(currentRefreshToken);
            request.setDeviceId(tokenStore.getDeviceId());

            String refreshUrl = config.getServerAuthUrl() + "/device-refresh";
            DeviceRefreshResponse response = httpClient.post(refreshUrl, request, DeviceRefreshResponse.class);

            if (response == null) {
                throw new AuthException("Empty refresh response");
            }

            tokenStore.updateTokens(
                    response.getAccessToken(),
                    response.getRefreshToken(),
                    response.getExpiresIn()
            );

            // Update persisted refresh token
            updateSavedRefreshToken(response.getRefreshToken());

            log.info("Access token refreshed successfully, new expiry in {} seconds", response.getExpiresIn());
        } catch (HttpClient.HttpException e) {
            if (e.getStatusCode() == 401) {
                log.error("Refresh token is invalid or revoked, clearing credentials");
                tokenStore.clear();
                credentialStore.delete();
                throw new AuthException("Session expired, please login again", e);
            }
            throw new AuthException("Token refresh failed: " + e.getMessage(), e);
        } catch (Exception e) {
            if (e instanceof AuthException) throw (AuthException) e;
            throw new AuthException("Token refresh failed: " + e.getMessage(), e);
        } finally {
            refreshInProgress = false;
        }
    }

    /**
     * Returns a valid access token, auto-refreshing if needed.
     */
    public String getValidAccessToken() throws AuthException {
        if (!isAuthenticated()) {
            throw new AuthException("Not authenticated");
        }
        refreshTokenIfNeeded();
        return tokenStore.getAccessToken();
    }

    /**
     * Checks if the agent is currently authenticated (has tokens).
     */
    public boolean isAuthenticated() {
        return tokenStore.hasTokens() && tokenStore.getDeviceId() != null;
    }

    /**
     * Returns the current device ID.
     */
    public String getDeviceId() {
        return tokenStore.getDeviceId();
    }

    /**
     * Clears all authentication state and removes persisted credentials.
     */
    public void logout() {
        log.info("Logging out, clearing all credentials");
        tokenStore.clear();
        credentialStore.delete();
        this.serverConfig = null;
        this.tenantName = null;
    }

    /**
     * Attempts to restore a session from saved credentials.
     *
     * @return true if credentials were loaded and a refresh was successful
     */
    public boolean tryLoadSavedCredentials() {
        CredentialStore.SavedCredentials saved = credentialStore.load();
        if (saved == null) {
            log.debug("No saved credentials found");
            return false;
        }

        if (saved.getDeviceId() == null || saved.getRefreshToken() == null) {
            log.warn("Saved credentials are incomplete, deleting");
            credentialStore.delete();
            return false;
        }

        log.info("Found saved credentials for device {}, attempting refresh...", saved.getDeviceId());

        // Restore minimal state for refresh to work
        tokenStore.updateTokens(null, saved.getRefreshToken(), 0, saved.getDeviceId());
        this.tenantName = saved.getTenantName();

        try {
            // Force a refresh since we have no access token
            DeviceRefreshRequest request = new DeviceRefreshRequest();
            request.setRefreshToken(saved.getRefreshToken());
            request.setDeviceId(saved.getDeviceId());

            String refreshUrl = config.getServerAuthUrl() + "/device-refresh";
            DeviceRefreshResponse response = httpClient.post(refreshUrl, request, DeviceRefreshResponse.class);

            if (response != null) {
                tokenStore.updateTokens(
                        response.getAccessToken(),
                        response.getRefreshToken(),
                        response.getExpiresIn()
                );
                updateSavedRefreshToken(response.getRefreshToken());
                log.info("Session restored from saved credentials");
                return true;
            }
        } catch (Exception e) {
            log.warn("Failed to restore session from saved credentials: {}", e.getMessage());
            tokenStore.clear();
        }

        return false;
    }

    private void saveCredentials(DeviceLoginResponse response) {
        CredentialStore.SavedCredentials saved = new CredentialStore.SavedCredentials(
                response.getDeviceId(),
                response.getRefreshToken(),
                config.getServerBaseUrl(),
                extractTenantName(response)
        );
        credentialStore.save(saved);
    }

    private void updateSavedRefreshToken(String newRefreshToken) {
        CredentialStore.SavedCredentials saved = credentialStore.load();
        if (saved != null) {
            saved.setRefreshToken(newRefreshToken);
            credentialStore.save(saved);
        }
    }

    private String extractTenantName(DeviceLoginResponse response) {
        if (response.getUser() != null && response.getUser().containsKey("tenant_name")) {
            return String.valueOf(response.getUser().get("tenant_name"));
        }
        return tenantName;
    }

    private String parseErrorMessage(HttpClient.HttpException e) {
        try {
            var node = httpClient.getObjectMapper().readTree(e.getMessage());
            if (node.has("error")) {
                return node.get("error").asText();
            }
        } catch (Exception ignored) {
            // Not JSON, use raw message
        }
        return switch (e.getStatusCode()) {
            case 401 -> "Неверные учётные данные или токен регистрации";
            case 403 -> "Доступ запрещён (учётная запись деактивирована)";
            case 404 -> "Тенант не найден";
            case 409 -> "Устройство уже зарегистрировано";
            default -> "Ошибка сервера (HTTP " + e.getStatusCode() + ")";
        };
    }

    // ---- DTOs ----

    @Data
    @NoArgsConstructor
    public static class DeviceLoginRequest {
        private String username;
        private String password;
        @JsonProperty("registration_token")
        private String registrationToken;
        @JsonProperty("device_info")
        private DeviceInfo deviceInfo;

        @Data
        @NoArgsConstructor
        public static class DeviceInfo {
            private String hostname;
            @JsonProperty("os_version")
            private String osVersion;
            @JsonProperty("agent_version")
            private String agentVersion;
            @JsonProperty("hardware_id")
            private String hardwareId;
        }
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DeviceLoginResponse {
        @JsonProperty("access_token")
        private String accessToken;
        @JsonProperty("refresh_token")
        private String refreshToken;
        @JsonProperty("token_type")
        private String tokenType;
        @JsonProperty("expires_in")
        private int expiresIn;
        @JsonProperty("device_id")
        private String deviceId;
        @JsonProperty("device_status")
        private String deviceStatus;
        private Map<String, Object> user;
        @JsonProperty("server_config")
        private ServerConfig serverConfig;
    }

    @Data
    @NoArgsConstructor
    public static class DeviceRefreshRequest {
        @JsonProperty("refresh_token")
        private String refreshToken;
        @JsonProperty("device_id")
        private String deviceId;
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DeviceRefreshResponse {
        @JsonProperty("access_token")
        private String accessToken;
        @JsonProperty("refresh_token")
        private String refreshToken;
        @JsonProperty("token_type")
        private String tokenType;
        @JsonProperty("expires_in")
        private int expiresIn;
    }

    /**
     * Authentication-specific exception.
     */
    public static class AuthException extends Exception {
        public AuthException(String message) {
            super(message);
        }

        public AuthException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
