package com.prg.auth.service;

import com.prg.auth.config.FrontendConfig;
import com.prg.auth.config.YandexOAuthConfig;
import com.prg.auth.exception.InvalidCredentialsException;
import com.prg.auth.repository.OAuthIdentityRepository;
import com.prg.auth.repository.RefreshTokenRepository;
import com.prg.auth.repository.UserOAuthLinkRepository;
import com.prg.auth.repository.UserRepository;
import com.prg.auth.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OAuthServiceTest {

    @Mock private YandexOAuthClient yandexClient;
    @Mock private OAuthIdentityRepository oauthIdentityRepository;
    @Mock private UserOAuthLinkRepository userOAuthLinkRepository;
    @Mock private UserRepository userRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private YandexOAuthConfig oauthConfig;
    @Mock private FrontendConfig frontendConfig;
    @Mock private AuditService auditService;
    @Mock private OAuthStateStore stateStore;

    private OAuthService oauthService;

    @BeforeEach
    void setUp() {
        oauthService = new OAuthService(
                yandexClient, oauthIdentityRepository,
                userOAuthLinkRepository, userRepository, refreshTokenRepository,
                jwtTokenProvider, oauthConfig,
                frontendConfig, auditService, stateStore
        );
    }

    @Test
    @DisplayName("handleCallback should throw InvalidCredentialsException for invalid state")
    void testHandleCallbackWithInvalidState() {
        when(stateStore.validateAndConsume("invalid-state")).thenReturn(false);

        assertThatThrownBy(() -> oauthService.handleCallback("code", "invalid-state", "127.0.0.1", "TestAgent"))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessageContaining("Invalid or expired OAuth state parameter");
    }

    @Test
    @DisplayName("getAuthorizationUrl should include state and URL-encoded redirect_uri")
    void testGetAuthorizationUrl() {
        when(stateStore.generateAndStore()).thenReturn("test-state-123");
        when(oauthConfig.getAuthorizeUrl()).thenReturn("https://oauth.yandex.ru/authorize");
        when(oauthConfig.getClientId()).thenReturn("client123");
        when(oauthConfig.getCallbackUrl()).thenReturn("https://example.com/callback");

        String url = oauthService.getAuthorizationUrl();

        assertThat(url).contains("response_type=code");
        assertThat(url).contains("client_id=client123");
        assertThat(url).contains("state=test-state-123");
        // redirect_uri should be URL-encoded
        assertThat(url).contains("redirect_uri=https%3A%2F%2Fexample.com%2Fcallback");
    }

}
