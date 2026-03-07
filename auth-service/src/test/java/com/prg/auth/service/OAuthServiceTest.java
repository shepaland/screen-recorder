package com.prg.auth.service;

import com.prg.auth.config.FrontendConfig;
import com.prg.auth.config.MailruOAuthConfig;
import com.prg.auth.config.YandexOAuthConfig;
import com.prg.auth.entity.OAuthIdentity;
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

import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OAuthServiceTest {

    @Mock private YandexOAuthClient yandexClient;
    @Mock private MailruOAuthClient mailruClient;
    @Mock private OAuthIdentityRepository oauthIdentityRepository;
    @Mock private UserOAuthLinkRepository userOAuthLinkRepository;
    @Mock private UserRepository userRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private YandexOAuthConfig oauthConfig;
    @Mock private MailruOAuthConfig mailruOAuthConfig;
    @Mock private FrontendConfig frontendConfig;
    @Mock private AuditService auditService;
    @Mock private OAuthStateStore stateStore;

    private OAuthService oauthService;

    @BeforeEach
    void setUp() {
        oauthService = new OAuthService(
                yandexClient, mailruClient, oauthIdentityRepository,
                userOAuthLinkRepository, userRepository, refreshTokenRepository,
                jwtTokenProvider, oauthConfig, mailruOAuthConfig,
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
    @DisplayName("handleMailruCallback should throw InvalidCredentialsException for invalid state")
    void testHandleMailruCallbackWithInvalidState() {
        when(stateStore.validateAndConsume("bad-state")).thenReturn(false);

        assertThatThrownBy(() -> oauthService.handleMailruCallback("code", "bad-state", "127.0.0.1", "TestAgent"))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessageContaining("Invalid or expired OAuth state parameter");
    }

    @Test
    @DisplayName("handleMailruCallback should return needs_onboarding for new user")
    void testHandleMailruCallbackNewUser() {
        // Arrange
        when(stateStore.validateAndConsume("valid-state")).thenReturn(true);
        when(mailruClient.exchangeCodeForToken("test-code")).thenReturn("mailru-token");
        when(mailruClient.getUserInfo("mailru-token")).thenReturn(Map.of(
                "id", "999",
                "email", "newuser@mail.ru",
                "name", "New User",
                "image", "https://filin.mail.ru/pic?d=test"
        ));

        OAuthIdentity savedIdentity = OAuthIdentity.builder()
                .id(java.util.UUID.randomUUID())
                .provider("mailru")
                .providerSub("999")
                .email("newuser@mail.ru")
                .name("New User")
                .avatarUrl("https://filin.mail.ru/pic?d=test")
                .build();

        when(oauthIdentityRepository.findByProviderAndProviderSub("mailru", "999"))
                .thenReturn(Optional.empty());
        when(oauthIdentityRepository.save(any(OAuthIdentity.class))).thenReturn(savedIdentity);
        when(userOAuthLinkRepository.findActiveLinksWithUserAndTenant(savedIdentity.getId()))
                .thenReturn(Collections.emptyList());
        when(jwtTokenProvider.generateOAuthIntermediateToken(any(), any(), any(), any(), any()))
                .thenReturn("intermediate-jwt-token");

        // Act
        OAuthService.OAuthCallbackResult result = oauthService.handleMailruCallback(
                "test-code", "valid-state", "127.0.0.1", "TestAgent");

        // Assert
        assertThat(result.getResponse().getStatus()).isEqualTo("needs_onboarding");
        assertThat(result.getResponse().getOauthToken()).isEqualTo("intermediate-jwt-token");
        assertThat(result.getResponse().getOauthUser().getEmail()).isEqualTo("newuser@mail.ru");
        assertThat(result.getResponse().getOauthUser().getName()).isEqualTo("New User");
        assertThat(result.getRawRefreshToken()).isNull();

        // Verify identity was created with provider=mailru
        verify(oauthIdentityRepository).save(argThat(identity ->
                "mailru".equals(identity.getProvider()) && "999".equals(identity.getProviderSub())
        ));
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

    @Test
    @DisplayName("getMailruAuthorizationUrl should include scope=userinfo and URL-encoded redirect_uri")
    void testGetMailruAuthorizationUrl() {
        when(stateStore.generateAndStore()).thenReturn("test-state-456");
        when(mailruOAuthConfig.getAuthorizeUrl()).thenReturn("https://oauth.mail.ru/login");
        when(mailruOAuthConfig.getClientId()).thenReturn("mailru-client");
        when(mailruOAuthConfig.getCallbackUrl()).thenReturn("https://example.com/mailru/callback");
        when(mailruOAuthConfig.getScope()).thenReturn("userinfo");

        String url = oauthService.getMailruAuthorizationUrl();

        assertThat(url).startsWith("https://oauth.mail.ru/login");
        assertThat(url).contains("response_type=code");
        assertThat(url).contains("client_id=mailru-client");
        assertThat(url).contains("scope=userinfo");
        assertThat(url).contains("state=test-state-456");
        assertThat(url).contains("redirect_uri=https%3A%2F%2Fexample.com%2Fmailru%2Fcallback");
    }
}
