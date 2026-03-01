package com.prg.auth.service;

import com.prg.auth.config.JwtConfig;
import com.prg.auth.dto.request.LoginRequest;
import com.prg.auth.dto.response.LoginResponse;
import com.prg.auth.dto.response.TokenResponse;
import com.prg.auth.entity.*;
import com.prg.auth.exception.InvalidCredentialsException;
import com.prg.auth.exception.RateLimitExceededException;
import com.prg.auth.exception.TokenExpiredException;
import com.prg.auth.repository.RefreshTokenRepository;
import com.prg.auth.repository.TenantRepository;
import com.prg.auth.repository.UserRepository;
import com.prg.auth.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private TenantRepository tenantRepository;
    @Mock private UserRepository userRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private JwtConfig jwtConfig;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private AuditService auditService;

    @InjectMocks
    private AuthService authService;

    private Tenant tenant;
    private User user;
    private Permission readPermission;
    private Role operatorRole;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(authService, "maxLoginAttempts", 5);
        ReflectionTestUtils.setField(authService, "loginAttemptWindow", 900L);

        tenant = Tenant.builder()
                .id(UUID.randomUUID())
                .name("Test Tenant")
                .slug("test-tenant")
                .isActive(true)
                .settings(Map.of())
                .createdTs(Instant.now())
                .updatedTs(Instant.now())
                .build();

        readPermission = Permission.builder()
                .id(UUID.randomUUID())
                .code("DASHBOARD:VIEW")
                .name("View dashboard")
                .resource("DASHBOARD")
                .action("VIEW")
                .build();

        operatorRole = Role.builder()
                .id(UUID.randomUUID())
                .tenant(tenant)
                .code("OPERATOR")
                .name("Operator")
                .isSystem(true)
                .permissions(Set.of(readPermission))
                .createdTs(Instant.now())
                .updatedTs(Instant.now())
                .build();

        user = User.builder()
                .id(UUID.randomUUID())
                .tenant(tenant)
                .username("testuser")
                .email("test@example.com")
                .passwordHash("$2a$12$hashedpassword")
                .firstName("Test")
                .lastName("User")
                .isActive(true)
                .roles(Set.of(operatorRole))
                .createdTs(Instant.now())
                .updatedTs(Instant.now())
                .build();
    }

    @Test
    @DisplayName("Login success - returns access token and user data")
    void loginSuccess() {
        LoginRequest request = LoginRequest.builder()
                .username("testuser")
                .password("Password123")
                .tenantSlug("test-tenant")
                .build();

        when(tenantRepository.findBySlugAndIsActiveTrue("test-tenant")).thenReturn(Optional.of(tenant));
        when(userRepository.findByTenantIdAndUsername(tenant.getId(), "testuser")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Password123", user.getPasswordHash())).thenReturn(true);
        when(jwtTokenProvider.generateAccessToken(any(), any(), anyString(), anyString(), anyList(), anyList(), anyList()))
                .thenReturn("mock-access-token");
        when(jwtTokenProvider.getAccessTokenTtl()).thenReturn(900L);
        when(jwtConfig.getRefreshTokenTtl()).thenReturn(2592000L);
        when(refreshTokenRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        AuthResult<LoginResponse> result = authService.login(request, "127.0.0.1", "Test-Agent");

        assertThat(result).isNotNull();
        assertThat(result.getResponse().getAccessToken()).isEqualTo("mock-access-token");
        assertThat(result.getResponse().getTokenType()).isEqualTo("Bearer");
        assertThat(result.getResponse().getExpiresIn()).isEqualTo(900L);
        assertThat(result.getResponse().getUser().getUsername()).isEqualTo("testuser");
        assertThat(result.getResponse().getUser().getEmail()).isEqualTo("test@example.com");
        assertThat(result.getRawRefreshToken()).isNotBlank();

        verify(userRepository).updateLastLoginTs(eq(user.getId()), any(Instant.class));
        verify(refreshTokenRepository).save(any(RefreshToken.class));
    }

    @Test
    @DisplayName("Login failure - invalid password throws InvalidCredentialsException")
    void loginInvalidPassword() {
        LoginRequest request = LoginRequest.builder()
                .username("testuser")
                .password("wrongpassword")
                .tenantSlug("test-tenant")
                .build();

        when(tenantRepository.findBySlugAndIsActiveTrue("test-tenant")).thenReturn(Optional.of(tenant));
        when(userRepository.findByTenantIdAndUsername(tenant.getId(), "testuser")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrongpassword", user.getPasswordHash())).thenReturn(false);

        assertThatThrownBy(() -> authService.login(request, "127.0.0.1", "Test-Agent"))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("Invalid username or password");
    }

    @Test
    @DisplayName("Login failure - user not found throws InvalidCredentialsException")
    void loginUserNotFound() {
        LoginRequest request = LoginRequest.builder()
                .username("nonexistent")
                .password("Password123")
                .tenantSlug("test-tenant")
                .build();

        when(tenantRepository.findBySlugAndIsActiveTrue("test-tenant")).thenReturn(Optional.of(tenant));
        when(userRepository.findByTenantIdAndUsername(tenant.getId(), "nonexistent")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(request, "127.0.0.1", "Test-Agent"))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("Invalid username or password");
    }

    @Test
    @DisplayName("Login failure - inactive user throws InvalidCredentialsException")
    void loginInactiveUser() {
        user.setIsActive(false);

        LoginRequest request = LoginRequest.builder()
                .username("testuser")
                .password("Password123")
                .tenantSlug("test-tenant")
                .build();

        when(tenantRepository.findBySlugAndIsActiveTrue("test-tenant")).thenReturn(Optional.of(tenant));
        when(userRepository.findByTenantIdAndUsername(tenant.getId(), "testuser")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.login(request, "127.0.0.1", "Test-Agent"))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("Account is disabled");
    }

    @Test
    @DisplayName("Login failure - tenant not found throws ResourceNotFoundException")
    void loginTenantNotFound() {
        LoginRequest request = LoginRequest.builder()
                .username("testuser")
                .password("Password123")
                .tenantSlug("nonexistent-tenant")
                .build();

        when(tenantRepository.findBySlugAndIsActiveTrue("nonexistent-tenant")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(request, "127.0.0.1", "Test-Agent"))
                .isInstanceOf(com.prg.auth.exception.ResourceNotFoundException.class)
                .hasMessageContaining("Tenant not found");
    }

    @Test
    @DisplayName("Refresh success - returns new access token with rotated refresh token")
    void refreshSuccess() {
        String rawToken = UUID.randomUUID().toString();
        String tokenHash = AuthService.sha256(rawToken);

        RefreshToken storedToken = RefreshToken.builder()
                .id(UUID.randomUUID())
                .user(user)
                .tenantId(tenant.getId())
                .tokenHash(tokenHash)
                .expiresAt(Instant.now().plusSeconds(86400))
                .isRevoked(false)
                .build();

        when(refreshTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(storedToken));
        when(tenantRepository.findById(tenant.getId())).thenReturn(Optional.of(tenant));
        when(jwtTokenProvider.generateAccessToken(any(), any(), anyString(), anyString(), anyList(), anyList(), anyList()))
                .thenReturn("new-access-token");
        when(jwtTokenProvider.getAccessTokenTtl()).thenReturn(900L);
        when(jwtConfig.getRefreshTokenTtl()).thenReturn(2592000L);
        when(refreshTokenRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        AuthResult<TokenResponse> result = authService.refresh(rawToken, "127.0.0.1", "Test-Agent");

        assertThat(result).isNotNull();
        assertThat(result.getResponse().getAccessToken()).isEqualTo("new-access-token");
        assertThat(result.getResponse().getTokenType()).isEqualTo("Bearer");
        assertThat(result.getRawRefreshToken()).isNotBlank();
        assertThat(result.getRawRefreshToken()).isNotEqualTo(rawToken);

        verify(refreshTokenRepository).revokeById(storedToken.getId());
        verify(refreshTokenRepository).save(any(RefreshToken.class));
    }

    @Test
    @DisplayName("Refresh failure - revoked token triggers reuse detection and revokes all user tokens")
    void refreshReuseDetection() {
        String rawToken = UUID.randomUUID().toString();
        String tokenHash = AuthService.sha256(rawToken);

        RefreshToken storedToken = RefreshToken.builder()
                .id(UUID.randomUUID())
                .user(user)
                .tenantId(tenant.getId())
                .tokenHash(tokenHash)
                .expiresAt(Instant.now().plusSeconds(86400))
                .isRevoked(true)
                .build();

        when(refreshTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(storedToken));

        assertThatThrownBy(() -> authService.refresh(rawToken, "127.0.0.1", "Test-Agent"))
                .isInstanceOf(TokenExpiredException.class)
                .hasMessageContaining("revoked");

        verify(refreshTokenRepository).revokeAllByUserId(user.getId());
    }

    @Test
    @DisplayName("Refresh failure - expired token throws TokenExpiredException")
    void refreshExpiredToken() {
        String rawToken = UUID.randomUUID().toString();
        String tokenHash = AuthService.sha256(rawToken);

        RefreshToken storedToken = RefreshToken.builder()
                .id(UUID.randomUUID())
                .user(user)
                .tenantId(tenant.getId())
                .tokenHash(tokenHash)
                .expiresAt(Instant.now().minusSeconds(3600))
                .isRevoked(false)
                .build();

        when(refreshTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(storedToken));

        assertThatThrownBy(() -> authService.refresh(rawToken, "127.0.0.1", "Test-Agent"))
                .isInstanceOf(TokenExpiredException.class);
    }

    @Test
    @DisplayName("Refresh failure - missing token throws TokenExpiredException")
    void refreshMissingToken() {
        assertThatThrownBy(() -> authService.refresh(null, "127.0.0.1", "Test-Agent"))
                .isInstanceOf(TokenExpiredException.class)
                .hasMessageContaining("missing");
    }

    @Test
    @DisplayName("SHA-256 produces consistent hash")
    void sha256Consistency() {
        String input = "test-token-value";
        String hash1 = AuthService.sha256(input);
        String hash2 = AuthService.sha256(input);

        assertThat(hash1).isEqualTo(hash2);
        assertThat(hash1).hasSize(64);
    }
}
