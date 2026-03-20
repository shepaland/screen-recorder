package com.prg.auth.service;

import com.prg.auth.config.JwtConfig;
import com.prg.auth.dto.request.LoginRequest;
import com.prg.auth.dto.response.LoginResponse;
import com.prg.auth.dto.response.MyTenantsResponse;
import com.prg.auth.dto.response.OAuthCallbackResponse;
import com.prg.auth.dto.response.RoleResponse;
import com.prg.auth.dto.response.TokenResponse;
import com.prg.auth.dto.response.UserResponse;
import com.prg.auth.entity.OAuthIdentity;
import com.prg.auth.entity.RefreshToken;
import com.prg.auth.entity.Tenant;
import com.prg.auth.entity.User;
import com.prg.auth.entity.UserOAuthLink;
import com.prg.auth.exception.AccessDeniedException;
import com.prg.auth.exception.InvalidCredentialsException;
import com.prg.auth.exception.RateLimitExceededException;
import com.prg.auth.exception.ResourceNotFoundException;
import com.prg.auth.exception.TokenExpiredException;
import com.prg.auth.repository.RefreshTokenRepository;
import com.prg.auth.repository.TenantRepository;
import com.prg.auth.repository.UserOAuthLinkRepository;
import com.prg.auth.repository.UserRepository;
import com.prg.auth.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserOAuthLinkRepository userOAuthLinkRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final JwtConfig jwtConfig;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    @Value("${prg.security.max-login-attempts}")
    private int maxLoginAttempts;

    @Value("${prg.security.login-attempt-window}")
    private long loginAttemptWindow;

    private final ConcurrentHashMap<String, List<Long>> loginAttempts = new ConcurrentHashMap<>();

    @Transactional
    public AuthResult<LoginResponse> login(LoginRequest request, String ipAddress, String userAgent) {
        String rateLimitKey = ipAddress + ":" + request.getUsername();
        checkRateLimit(rateLimitKey);

        Tenant tenant;
        User user;

        // Username = email, globally unique. Single lookup regardless of tenant.
        String normalizedEmail = request.getUsername().trim().toLowerCase();
        Optional<User> found = userRepository.findActiveByEmail(normalizedEmail);
        user = found.orElse(null);
        tenant = user != null ? user.getTenant() : null;

        if (tenant == null) {
            recordLoginAttempt(rateLimitKey);
            throw new InvalidCredentialsException("Invalid email or password");
        }

        if (user == null) {
            recordLoginAttempt(rateLimitKey);
            auditService.logAction(tenant.getId(), null, "LOGIN_FAILED", "AUTH", null,
                    Map.of("username", request.getUsername(), "reason", "user_not_found"),
                    ipAddress, userAgent, getCorrelationId());
            throw new InvalidCredentialsException("Invalid username or password");
        }

        if (!user.getIsActive()) {
            recordLoginAttempt(rateLimitKey);
            auditService.logAction(tenant.getId(), user.getId(), "LOGIN_FAILED", "AUTH", null,
                    Map.of("reason", "account_disabled"),
                    ipAddress, userAgent, getCorrelationId());
            throw new InvalidCredentialsException("Invalid username or password");
        }

        // Reject password login for OAuth users
        if ("oauth".equals(user.getAuthProvider())) {
            auditService.logAction(tenant.getId(), user.getId(), "LOGIN_FAILED", "AUTH", null,
                    Map.of("reason", "oauth_user_password_login"),
                    ipAddress, userAgent, getCorrelationId());
            throw new AccessDeniedException(
                    "This account uses OAuth authentication. Please log in via OAuth provider.",
                    "OAUTH_USER_PASSWORD_LOGIN_DENIED");
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            recordLoginAttempt(rateLimitKey);
            auditService.logAction(tenant.getId(), user.getId(), "LOGIN_FAILED", "AUTH", null,
                    Map.of("reason", "invalid_password"),
                    ipAddress, userAgent, getCorrelationId());
            throw new InvalidCredentialsException("Invalid username or password");
        }

        loginAttempts.remove(rateLimitKey);

        List<String> roles = user.getRoles().stream().map(r -> r.getCode()).toList();
        List<String> permissions = user.getRoles().stream()
                .flatMap(r -> r.getPermissions().stream())
                .map(p -> p.getCode())
                .distinct().sorted().toList();
        List<String> scopes = determineScopesForRoles(roles);

        String accessToken = jwtTokenProvider.generateAccessToken(
                user.getId(), tenant.getId(), user.getUsername(), user.getEmail(),
                roles, permissions, scopes);

        String rawRefreshToken = UUID.randomUUID().toString();
        String tokenHash = sha256(rawRefreshToken);

        RefreshToken refreshToken = RefreshToken.builder()
                .user(user)
                .tenantId(tenant.getId())
                .tokenHash(tokenHash)
                .deviceInfo(userAgent)
                .ipAddress(ipAddress)
                .expiresAt(Instant.now().plusSeconds(jwtConfig.getRefreshTokenTtl()))
                .isRevoked(false)
                .build();
        refreshTokenRepository.save(refreshToken);

        userRepository.updateLastLoginTs(user.getId(), Instant.now());

        auditService.logAction(tenant.getId(), user.getId(), "LOGIN", "AUTH", user.getId(),
                Map.of("roles", roles), ipAddress, userAgent, getCorrelationId());

        List<RoleResponse> roleResponses = user.getRoles().stream()
                .map(r -> RoleResponse.builder().code(r.getCode()).name(r.getName()).build())
                .toList();

        UserResponse userResponse = UserResponse.builder()
                .id(user.getId())
                .tenantId(tenant.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .authProvider(user.getAuthProvider())
                .emailVerified(user.getEmailVerified())
                .isPasswordSet(user.getIsPasswordSet())
                .roles(roleResponses)
                .permissions(permissions)
                .settings(user.getSettings())
                .build();

        LoginResponse loginResponse = LoginResponse.builder()
                .accessToken(accessToken)
                .tokenType("Bearer")
                .expiresIn(jwtTokenProvider.getAccessTokenTtl())
                .user(userResponse)
                .build();

        return AuthResult.<LoginResponse>builder()
                .response(loginResponse)
                .rawRefreshToken(rawRefreshToken)
                .build();
    }

    @Transactional
    public AuthResult<TokenResponse> refresh(String rawRefreshToken, String ipAddress, String userAgent) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new TokenExpiredException("Refresh token is missing", "REFRESH_TOKEN_MISSING");
        }

        String tokenHash = sha256(rawRefreshToken);
        RefreshToken storedToken = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new TokenExpiredException("Refresh token not found or expired", "REFRESH_TOKEN_EXPIRED"));

        if (storedToken.getIsRevoked()) {
            log.warn("Refresh token reuse detected for user: {}. Revoking all tokens.", storedToken.getUser().getId());
            refreshTokenRepository.revokeAllByUserId(storedToken.getUser().getId());
            throw new TokenExpiredException("Refresh token has been revoked. All sessions terminated.", "REFRESH_TOKEN_REVOKED");
        }

        if (storedToken.getExpiresAt().isBefore(Instant.now())) {
            refreshTokenRepository.revokeById(storedToken.getId());
            throw new TokenExpiredException("Refresh token has expired", "REFRESH_TOKEN_EXPIRED");
        }

        refreshTokenRepository.revokeById(storedToken.getId());

        User user = storedToken.getUser();
        UUID tenantId = storedToken.getTenantId();
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found", "TENANT_NOT_FOUND"));

        if (!user.getIsActive() || !tenant.getIsActive()) {
            throw new InvalidCredentialsException("Account or tenant is disabled", "ACCOUNT_DISABLED");
        }

        List<String> roles = user.getRoles().stream().map(r -> r.getCode()).toList();
        List<String> permissions = user.getRoles().stream()
                .flatMap(r -> r.getPermissions().stream())
                .map(p -> p.getCode())
                .distinct().sorted().toList();
        List<String> scopes = determineScopesForRoles(roles);

        String accessToken = jwtTokenProvider.generateAccessToken(
                user.getId(), tenant.getId(), user.getUsername(), user.getEmail(),
                roles, permissions, scopes);

        String newRawRefreshToken = UUID.randomUUID().toString();
        String newTokenHash = sha256(newRawRefreshToken);

        RefreshToken newRefreshToken = RefreshToken.builder()
                .user(user)
                .tenantId(tenant.getId())
                .tokenHash(newTokenHash)
                .deviceInfo(userAgent)
                .ipAddress(ipAddress)
                .expiresAt(Instant.now().plusSeconds(jwtConfig.getRefreshTokenTtl()))
                .isRevoked(false)
                .build();
        refreshTokenRepository.save(newRefreshToken);

        auditService.logAction(tenantId, user.getId(), "TOKEN_REFRESHED", "AUTH", null,
                null, ipAddress, userAgent, getCorrelationId());

        TokenResponse tokenResponse = TokenResponse.builder()
                .accessToken(accessToken)
                .tokenType("Bearer")
                .expiresIn(jwtTokenProvider.getAccessTokenTtl())
                .build();

        return AuthResult.<TokenResponse>builder()
                .response(tokenResponse)
                .rawRefreshToken(newRawRefreshToken)
                .build();
    }

    @Transactional
    public void logout(String rawRefreshToken, UUID userId, UUID tenantId, String ipAddress, String userAgent) {
        if (rawRefreshToken != null && !rawRefreshToken.isBlank()) {
            String tokenHash = sha256(rawRefreshToken);
            refreshTokenRepository.findByTokenHash(tokenHash)
                    .ifPresent(token -> refreshTokenRepository.revokeById(token.getId()));
        }
        auditService.logAction(tenantId, userId, "LOGOUT", "AUTH", null,
                null, ipAddress, userAgent, getCorrelationId());
    }

    @Transactional
    public void logoutAll(UUID userId, UUID tenantId, String ipAddress, String userAgent) {
        refreshTokenRepository.revokeAllByUserId(userId);
        auditService.logAction(tenantId, userId, "LOGOUT", "AUTH", null,
                Map.of("scope", "all_sessions"), ipAddress, userAgent, getCorrelationId());
    }

    // --- Helper methods ---

    private void checkRateLimit(String key) {
        List<Long> attempts = loginAttempts.get(key);
        if (attempts == null) return;
        long windowStart = System.currentTimeMillis() - (loginAttemptWindow * 1000);
        long recentAttempts = attempts.stream().filter(ts -> ts > windowStart).count();
        if (recentAttempts >= maxLoginAttempts) {
            throw new RateLimitExceededException("Too many login attempts. Please try again later.");
        }
    }

    private void recordLoginAttempt(String key) {
        loginAttempts.computeIfAbsent(key, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(System.currentTimeMillis());
        long windowStart = System.currentTimeMillis() - (loginAttemptWindow * 1000);
        loginAttempts.computeIfPresent(key, (k, v) -> {
            v.removeIf(ts -> ts < windowStart);
            return v.isEmpty() ? null : v;
        });
    }

    private List<String> determineScopesForRoles(List<String> roles) {
        if (roles.contains("SUPER_ADMIN")) return List.of("global");
        if (roles.contains("OPERATOR")) return List.of("own");
        return List.of("tenant");
    }

    public static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    private UUID getCorrelationId() {
        String cid = MDC.get("correlation_id");
        if (cid != null) {
            try { return UUID.fromString(cid); } catch (IllegalArgumentException e) { return null; }
        }
        return null;
    }

    public long getRefreshTokenTtl() {
        return jwtConfig.getRefreshTokenTtl();
    }

    /**
     * Switch the current authenticated user to a different tenant.
     * Supports both OAuth and password users.
     * OAuth users are resolved via OAuthIdentity links; password users via username across tenants.
     */
    @Transactional
    public AuthResult<LoginResponse> switchTenant(UUID currentUserId, UUID targetTenantId,
                                                   String ipAddress, String userAgent) {
        User targetUser;
        Tenant targetTenant;

        // Try OAuth path first
        UserOAuthLink currentLink = userOAuthLinkRepository.findByUserId(currentUserId).orElse(null);

        if (currentLink != null) {
            // OAuth user: find target via identity links
            OAuthIdentity identity = currentLink.getOauthIdentity();
            List<UserOAuthLink> activeLinks = userOAuthLinkRepository.findActiveLinksWithUserAndTenant(identity.getId());
            UserOAuthLink targetLink = activeLinks.stream()
                    .filter(link -> link.getUser().getTenant().getId().equals(targetTenantId))
                    .findFirst()
                    .orElseThrow(() -> new AccessDeniedException(
                            "You do not have access to the requested tenant"));
            targetUser = targetLink.getUser();
        } else {
            // Password user: find target via username across all tenants
            User currentUser = userRepository.findById(currentUserId)
                    .orElseThrow(() -> new ResourceNotFoundException("User not found", "USER_NOT_FOUND"));
            List<User> allUsers = userRepository.findActiveUsersByUsername(currentUser.getUsername());
            targetUser = allUsers.stream()
                    .filter(u -> u.getTenant().getId().equals(targetTenantId))
                    .findFirst()
                    .orElseThrow(() -> new AccessDeniedException(
                            "You do not have access to the requested tenant"));
        }

        targetTenant = targetUser.getTenant();

        if (!targetUser.getIsActive()) {
            throw new AccessDeniedException("Your account in the target tenant is disabled");
        }
        if (!targetTenant.getIsActive()) {
            throw new AccessDeniedException("The target tenant is disabled");
        }

        // Revoke current refresh tokens
        refreshTokenRepository.revokeAllByUserId(currentUserId);

        // Generate new tokens for the target tenant
        List<String> roles = targetUser.getRoles().stream().map(r -> r.getCode()).toList();
        List<String> permissions = targetUser.getRoles().stream()
                .flatMap(r -> r.getPermissions().stream())
                .map(p -> p.getCode())
                .distinct().sorted().toList();
        List<String> scopes = determineScopesForRoles(roles);

        String accessToken = jwtTokenProvider.generateAccessToken(
                targetUser.getId(), targetTenant.getId(), targetUser.getUsername(), targetUser.getEmail(),
                roles, permissions, scopes);

        String rawRefreshToken = UUID.randomUUID().toString();
        String tokenHash = sha256(rawRefreshToken);

        RefreshToken refreshToken = RefreshToken.builder()
                .user(targetUser)
                .tenantId(targetTenant.getId())
                .tokenHash(tokenHash)
                .deviceInfo(userAgent)
                .ipAddress(ipAddress)
                .expiresAt(Instant.now().plusSeconds(jwtConfig.getRefreshTokenTtl()))
                .isRevoked(false)
                .build();
        refreshTokenRepository.save(refreshToken);

        userRepository.updateLastLoginTs(targetUser.getId(), Instant.now());

        auditService.logAction(targetTenant.getId(), targetUser.getId(), "TENANT_SWITCHED", "AUTH", targetUser.getId(),
                Map.of("from_user_id", currentUserId.toString(), "to_tenant_id", targetTenantId.toString()),
                ipAddress, userAgent, getCorrelationId());

        List<RoleResponse> roleResponses = targetUser.getRoles().stream()
                .map(r -> RoleResponse.builder().code(r.getCode()).name(r.getName()).build())
                .toList();

        UserResponse userResponse = UserResponse.builder()
                .id(targetUser.getId())
                .tenantId(targetTenant.getId())
                .username(targetUser.getUsername())
                .email(targetUser.getEmail())
                .firstName(targetUser.getFirstName())
                .lastName(targetUser.getLastName())
                .authProvider(targetUser.getAuthProvider())
                .emailVerified(targetUser.getEmailVerified())
                .isPasswordSet(targetUser.getIsPasswordSet())
                .roles(roleResponses)
                .permissions(permissions)
                .settings(targetUser.getSettings())
                .build();

        LoginResponse loginResponse = LoginResponse.builder()
                .accessToken(accessToken)
                .tokenType("Bearer")
                .expiresIn(jwtTokenProvider.getAccessTokenTtl())
                .user(userResponse)
                .build();

        log.info("Tenant switched: user_id={} -> tenant_id={}, target_user_id={}",
                currentUserId, targetTenantId, targetUser.getId());

        return AuthResult.<LoginResponse>builder()
                .response(loginResponse)
                .rawRefreshToken(rawRefreshToken)
                .build();
    }

    /**
     * Get all tenants accessible to the current user via OAuth identity links.
     */
    @Transactional(readOnly = true)
    public MyTenantsResponse getMyTenants(UUID currentUserId) {
        UserOAuthLink currentLink = userOAuthLinkRepository.findByUserId(currentUserId)
                .orElse(null);

        if (currentLink == null) {
            // Password user — find ALL tenants where this username has an active account
            User currentUser = userRepository.findById(currentUserId)
                    .orElseThrow(() -> new ResourceNotFoundException("User not found", "USER_NOT_FOUND"));
            List<User> allUsers = userRepository.findActiveUsersByUsername(currentUser.getUsername());
            List<OAuthCallbackResponse.TenantPreview> tenants = allUsers.stream()
                    .map(user -> {
                        Tenant tenant = user.getTenant();
                        String primaryRole = user.getRoles().stream()
                                .map(r -> r.getCode())
                                .findFirst()
                                .orElse(null);
                        return OAuthCallbackResponse.TenantPreview.builder()
                                .id(tenant.getId())
                                .name(tenant.getName())
                                .slug(tenant.getSlug())
                                .role(primaryRole)
                                .isCurrent(user.getId().equals(currentUserId))
                                .createdTs(tenant.getCreatedTs())
                                .build();
                    })
                    .toList();
            return MyTenantsResponse.builder()
                    .tenants(tenants)
                    .build();
        }

        OAuthIdentity identity = currentLink.getOauthIdentity();
        List<UserOAuthLink> activeLinks = userOAuthLinkRepository.findActiveLinksWithUserAndTenant(identity.getId());

        List<OAuthCallbackResponse.TenantPreview> tenants = activeLinks.stream()
                .map(link -> {
                    User user = link.getUser();
                    Tenant tenant = user.getTenant();
                    String primaryRole = user.getRoles().stream()
                            .map(r -> r.getCode())
                            .findFirst()
                            .orElse(null);
                    return OAuthCallbackResponse.TenantPreview.builder()
                            .id(tenant.getId())
                            .name(tenant.getName())
                            .slug(tenant.getSlug())
                            .role(primaryRole)
                            .isCurrent(user.getId().equals(currentUserId))
                            .createdTs(tenant.getCreatedTs())
                            .build();
                })
                .toList();

        return MyTenantsResponse.builder()
                .tenants(tenants)
                .build();
    }
}
