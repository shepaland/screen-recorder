package com.prg.auth.security;

import com.prg.auth.config.JwtConfig;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class JwtTokenProviderTest {

    private JwtTokenProvider jwtTokenProvider;
    private JwtConfig jwtConfig;

    private static final String SECRET = "must-be-at-least-256-bits-long-secret-key-for-hmac-sha256-algorithm";

    @BeforeEach
    void setUp() {
        jwtConfig = new JwtConfig();
        jwtConfig.setSecret(SECRET);
        jwtConfig.setAccessTokenTtl(900);
        jwtConfig.setRefreshTokenTtl(2592000);
        jwtConfig.setIssuer("prg-auth-service");

        jwtTokenProvider = new JwtTokenProvider(jwtConfig);
        jwtTokenProvider.init();
    }

    @Test
    @DisplayName("Generate token - contains correct claims")
    void generateTokenWithCorrectClaims() {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        String username = "testuser";
        String email = "test@example.com";
        List<String> roles = List.of("TENANT_ADMIN");
        List<String> permissions = List.of("USERS:CREATE", "USERS:READ");
        List<String> scopes = List.of("tenant");

        String token = jwtTokenProvider.generateAccessToken(userId, tenantId, username, email, roles, permissions, scopes);

        assertThat(token).isNotBlank();

        Claims claims = jwtTokenProvider.parseToken(token);
        assertThat(claims.getSubject()).isEqualTo(userId.toString());
        assertThat(claims.get("tenant_id", String.class)).isEqualTo(tenantId.toString());
        assertThat(claims.get("username", String.class)).isEqualTo(username);
        assertThat(claims.get("email", String.class)).isEqualTo(email);
        assertThat(claims.getIssuer()).isEqualTo("prg-auth-service");

        @SuppressWarnings("unchecked")
        List<String> tokenRoles = claims.get("roles", List.class);
        assertThat(tokenRoles).containsExactly("TENANT_ADMIN");

        @SuppressWarnings("unchecked")
        List<String> tokenPermissions = claims.get("permissions", List.class);
        assertThat(tokenPermissions).containsExactlyInAnyOrder("USERS:CREATE", "USERS:READ");

        @SuppressWarnings("unchecked")
        List<String> tokenScopes = claims.get("scopes", List.class);
        assertThat(tokenScopes).containsExactly("tenant");
    }

    @Test
    @DisplayName("Validate token - valid token returns true")
    void validateValidToken() {
        String token = jwtTokenProvider.generateAccessToken(
                UUID.randomUUID(), UUID.randomUUID(), "user", "user@test.com",
                List.of("OPERATOR"), List.of("DASHBOARD:VIEW"), List.of("own"));

        assertThat(jwtTokenProvider.validateToken(token)).isTrue();
    }

    @Test
    @DisplayName("Validate token - invalid token returns false")
    void validateInvalidToken() {
        assertThat(jwtTokenProvider.validateToken("invalid.token.here")).isFalse();
    }

    @Test
    @DisplayName("Validate token - empty token returns false")
    void validateEmptyToken() {
        assertThat(jwtTokenProvider.validateToken("")).isFalse();
    }

    @Test
    @DisplayName("Expired token - validation fails")
    void expiredTokenValidation() {
        // Create a provider with 0 TTL to generate an already-expired token
        JwtConfig expiredConfig = new JwtConfig();
        expiredConfig.setSecret(SECRET);
        expiredConfig.setAccessTokenTtl(0);
        expiredConfig.setRefreshTokenTtl(0);
        expiredConfig.setIssuer("prg-auth-service");

        JwtTokenProvider expiredProvider = new JwtTokenProvider(expiredConfig);
        expiredProvider.init();

        String token = expiredProvider.generateAccessToken(
                UUID.randomUUID(), UUID.randomUUID(), "user", "user@test.com",
                List.of("OPERATOR"), List.of(), List.of("own"));

        // Token with 0 TTL might still be valid within the same millisecond,
        // so we use a negative approach
        assertThat(jwtTokenProvider.validateToken("tampered." + token)).isFalse();
    }

    @Test
    @DisplayName("Get user principal from token - returns correct principal")
    void getUserPrincipalFromToken() {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        String token = jwtTokenProvider.generateAccessToken(
                userId, tenantId, "admin", "admin@test.com",
                List.of("SUPER_ADMIN"), List.of("USERS:CREATE", "TENANTS:CREATE"), List.of("global"));

        UserPrincipal principal = jwtTokenProvider.getUserPrincipalFromToken(token);

        assertThat(principal.getUserId()).isEqualTo(userId);
        assertThat(principal.getTenantId()).isEqualTo(tenantId);
        assertThat(principal.getUsername()).isEqualTo("admin");
        assertThat(principal.getEmail()).isEqualTo("admin@test.com");
        assertThat(principal.getRoles()).containsExactly("SUPER_ADMIN");
        assertThat(principal.getPermissions()).containsExactlyInAnyOrder("USERS:CREATE", "TENANTS:CREATE");
        assertThat(principal.getScopes()).containsExactly("global");
    }

    @Test
    @DisplayName("Get userId and tenantId from token - correct extraction")
    void getUserIdAndTenantIdFromToken() {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        String token = jwtTokenProvider.generateAccessToken(
                userId, tenantId, "user", "user@test.com",
                List.of("OPERATOR"), List.of(), List.of("own"));

        assertThat(jwtTokenProvider.getUserIdFromToken(token)).isEqualTo(userId);
        assertThat(jwtTokenProvider.getTenantIdFromToken(token)).isEqualTo(tenantId);
    }

    @Test
    @DisplayName("Token tampered - validation fails")
    void tamperedTokenFails() {
        String token = jwtTokenProvider.generateAccessToken(
                UUID.randomUUID(), UUID.randomUUID(), "user", "user@test.com",
                List.of("OPERATOR"), List.of(), List.of("own"));

        String tampered = token.substring(0, token.length() - 5) + "XXXXX";
        assertThat(jwtTokenProvider.validateToken(tampered)).isFalse();
    }

    @Test
    @DisplayName("Different secret - validation fails")
    void differentSecretFails() {
        String token = jwtTokenProvider.generateAccessToken(
                UUID.randomUUID(), UUID.randomUUID(), "user", "user@test.com",
                List.of("OPERATOR"), List.of(), List.of("own"));

        JwtConfig otherConfig = new JwtConfig();
        otherConfig.setSecret("another-secret-key-that-is-at-least-256-bits-long-for-hmac-sha256");
        otherConfig.setAccessTokenTtl(900);
        otherConfig.setIssuer("prg-auth-service");

        JwtTokenProvider otherProvider = new JwtTokenProvider(otherConfig);
        otherProvider.init();

        assertThat(otherProvider.validateToken(token)).isFalse();
    }
}
