package com.prg.agent.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.prg.agent.config.AgentConfig;
import com.prg.agent.config.ServerConfig;
import com.prg.agent.util.HttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthManagerTest {

    @Mock
    private HttpClient httpClient;

    @Mock
    private CredentialStore credentialStore;

    private AgentConfig config;
    private AuthManager authManager;

    @BeforeEach
    void setUp() {
        config = new AgentConfig();
        config.setServerBaseUrl("https://test-server.example.com");
        config.setServerAuthUrl("https://test-server.example.com/api/v1/auth");

        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        when(httpClient.getObjectMapper()).thenReturn(mapper);

        authManager = new AuthManager(config, httpClient, credentialStore);
    }

    @Test
    void testLogin_success_storesTokens() throws Exception {
        // Given
        AuthManager.DeviceLoginResponse response = new AuthManager.DeviceLoginResponse();
        response.setAccessToken("access_token_123");
        response.setRefreshToken("refresh_token_456");
        response.setTokenType("Bearer");
        response.setExpiresIn(900);
        response.setDeviceId("device-uuid-123");
        response.setDeviceStatus("online");

        ServerConfig serverConfig = new ServerConfig();
        serverConfig.setHeartbeatIntervalSec(30);
        serverConfig.setCaptureFps(5);
        response.setServerConfig(serverConfig);

        when(httpClient.post(
                eq("https://test-server.example.com/api/v1/auth/device-login"),
                any(AuthManager.DeviceLoginRequest.class),
                eq(AuthManager.DeviceLoginResponse.class)))
                .thenReturn(response);

        // When
        AuthManager.DeviceLoginResponse result = authManager.login("reg_token", "admin", "password");

        // Then
        assertNotNull(result);
        assertEquals("access_token_123", result.getAccessToken());
        assertEquals("device-uuid-123", result.getDeviceId());
        assertTrue(authManager.isAuthenticated());
        assertEquals("device-uuid-123", authManager.getDeviceId());

        // Verify credentials were saved
        verify(credentialStore).save(any(CredentialStore.SavedCredentials.class));
    }

    @Test
    void testLogin_invalidToken_throwsAuthException() throws Exception {
        // Given
        when(httpClient.post(
                anyString(),
                any(AuthManager.DeviceLoginRequest.class),
                eq(AuthManager.DeviceLoginResponse.class)))
                .thenThrow(new HttpClient.HttpException(401, "Invalid registration token"));

        // When / Then
        AuthManager.AuthException exception = assertThrows(
                AuthManager.AuthException.class,
                () -> authManager.login("invalid_token", "admin", "password")
        );

        assertTrue(exception.getMessage().contains("Неверные учётные данные"));
        assertFalse(authManager.isAuthenticated());
    }

    @Test
    void testRefreshToken_success_updatesTokens() throws Exception {
        // Given - First login successfully
        AuthManager.DeviceLoginResponse loginResponse = new AuthManager.DeviceLoginResponse();
        loginResponse.setAccessToken("old_access");
        loginResponse.setRefreshToken("old_refresh");
        loginResponse.setExpiresIn(1); // Expires in 1 second (will be expiring soon)
        loginResponse.setDeviceId("device-123");

        when(httpClient.post(
                contains("device-login"),
                any(AuthManager.DeviceLoginRequest.class),
                eq(AuthManager.DeviceLoginResponse.class)))
                .thenReturn(loginResponse);

        authManager.login("token", "user", "pass");

        // Prepare refresh response
        AuthManager.DeviceRefreshResponse refreshResponse = new AuthManager.DeviceRefreshResponse();
        refreshResponse.setAccessToken("new_access");
        refreshResponse.setRefreshToken("new_refresh");
        refreshResponse.setExpiresIn(900);

        when(httpClient.post(
                contains("device-refresh"),
                any(AuthManager.DeviceRefreshRequest.class),
                eq(AuthManager.DeviceRefreshResponse.class)))
                .thenReturn(refreshResponse);

        when(credentialStore.load()).thenReturn(
                new CredentialStore.SavedCredentials("device-123", "old_refresh", "https://test", "TestTenant"));

        // When
        String validToken = authManager.getValidAccessToken();

        // Then
        assertEquals("new_access", validToken);
        verify(httpClient).post(contains("device-refresh"),
                any(AuthManager.DeviceRefreshRequest.class),
                eq(AuthManager.DeviceRefreshResponse.class));
    }

    @Test
    void testTryLoadSavedCredentials_noFile_returnsFalse() {
        // Given
        when(credentialStore.load()).thenReturn(null);

        // When
        boolean result = authManager.tryLoadSavedCredentials();

        // Then
        assertFalse(result);
        assertFalse(authManager.isAuthenticated());
    }

    @Test
    void testGetValidAccessToken_notExpired_returnsExisting() throws Exception {
        // Given - Login successfully with long-lived token
        AuthManager.DeviceLoginResponse loginResponse = new AuthManager.DeviceLoginResponse();
        loginResponse.setAccessToken("valid_token");
        loginResponse.setRefreshToken("refresh");
        loginResponse.setExpiresIn(3600); // 1 hour
        loginResponse.setDeviceId("device-123");

        when(httpClient.post(
                contains("device-login"),
                any(AuthManager.DeviceLoginRequest.class),
                eq(AuthManager.DeviceLoginResponse.class)))
                .thenReturn(loginResponse);

        authManager.login("token", "user", "pass");

        // When
        String accessToken = authManager.getValidAccessToken();

        // Then
        assertEquals("valid_token", accessToken);

        // Verify no refresh was attempted (only login call)
        verify(httpClient, times(1)).post(anyString(), any(), any());
    }

    @Test
    void testGetValidAccessToken_expiringSoon_refreshes() throws Exception {
        // Given - Login with token that expires in 60 seconds (below 120s threshold)
        AuthManager.DeviceLoginResponse loginResponse = new AuthManager.DeviceLoginResponse();
        loginResponse.setAccessToken("expiring_token");
        loginResponse.setRefreshToken("refresh_token");
        loginResponse.setExpiresIn(60); // Below 120s threshold
        loginResponse.setDeviceId("device-123");

        when(httpClient.post(
                contains("device-login"),
                any(AuthManager.DeviceLoginRequest.class),
                eq(AuthManager.DeviceLoginResponse.class)))
                .thenReturn(loginResponse);

        authManager.login("token", "user", "pass");

        // Prepare refresh response
        AuthManager.DeviceRefreshResponse refreshResponse = new AuthManager.DeviceRefreshResponse();
        refreshResponse.setAccessToken("new_access_token");
        refreshResponse.setRefreshToken("new_refresh_token");
        refreshResponse.setExpiresIn(900);

        when(httpClient.post(
                contains("device-refresh"),
                any(AuthManager.DeviceRefreshRequest.class),
                eq(AuthManager.DeviceRefreshResponse.class)))
                .thenReturn(refreshResponse);

        when(credentialStore.load()).thenReturn(
                new CredentialStore.SavedCredentials("device-123", "refresh_token", "https://test", "TestTenant"));

        // When
        String result = authManager.getValidAccessToken();

        // Then
        assertEquals("new_access_token", result);
    }

    @Test
    void testLogout_clearsEverything() throws Exception {
        // Given - Login first
        AuthManager.DeviceLoginResponse loginResponse = new AuthManager.DeviceLoginResponse();
        loginResponse.setAccessToken("token");
        loginResponse.setRefreshToken("refresh");
        loginResponse.setExpiresIn(3600);
        loginResponse.setDeviceId("device-123");

        when(httpClient.post(
                contains("device-login"),
                any(AuthManager.DeviceLoginRequest.class),
                eq(AuthManager.DeviceLoginResponse.class)))
                .thenReturn(loginResponse);

        authManager.login("token", "user", "pass");
        assertTrue(authManager.isAuthenticated());

        // When
        authManager.logout();

        // Then
        assertFalse(authManager.isAuthenticated());
        assertNull(authManager.getDeviceId());
        verify(credentialStore).delete();
    }
}
