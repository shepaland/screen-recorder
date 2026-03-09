package com.prg.auth.service;

import com.prg.auth.config.FrontendConfig;
import com.prg.auth.config.YandexOAuthConfig;
import com.prg.auth.dto.response.LoginResponse;
import com.prg.auth.dto.response.OAuthCallbackResponse;
import com.prg.auth.dto.response.RoleResponse;
import com.prg.auth.dto.response.UserResponse;
import com.prg.auth.entity.OAuthIdentity;
import com.prg.auth.entity.RefreshToken;
import com.prg.auth.entity.Tenant;
import com.prg.auth.entity.User;
import com.prg.auth.entity.UserOAuthLink;
import com.prg.auth.exception.InvalidCredentialsException;
import com.prg.auth.exception.ResourceNotFoundException;
import com.prg.auth.repository.OAuthIdentityRepository;
import com.prg.auth.repository.RefreshTokenRepository;
import com.prg.auth.repository.UserOAuthLinkRepository;
import com.prg.auth.repository.UserRepository;
import com.prg.auth.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class OAuthService {

    private final YandexOAuthClient yandexClient;
    private final OAuthIdentityRepository oauthIdentityRepository;
    private final UserOAuthLinkRepository userOAuthLinkRepository;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final YandexOAuthConfig oauthConfig;
    private final FrontendConfig frontendConfig;
    private final AuditService auditService;
    private final OAuthStateStore stateStore;

    public static final String PROVIDER_YANDEX = "yandex";

    // ======================== Authorization URLs ========================

    /**
     * Build Yandex authorization URL for redirect.
     * State parameter is stored server-side for CSRF validation on callback.
     * redirect_uri is URL-encoded.
     */
    public String getAuthorizationUrl() {
        String state = stateStore.generateAndStore();
        return oauthConfig.getAuthorizeUrl()
                + "?response_type=code"
                + "&client_id=" + oauthConfig.getClientId()
                + "&redirect_uri=" + urlEncode(oauthConfig.getCallbackUrl())
                + "&state=" + state;
    }

    // ======================== Callbacks ========================

    /**
     * Handle Yandex OAuth callback: validate state, exchange code, get user info, determine next step.
     */
    @Transactional
    public OAuthCallbackResult handleCallback(String code, String state, String ipAddress, String userAgent) {
        // Validate state parameter (CSRF protection)
        validateState(state);

        // 1. Exchange code for Yandex access token
        String yandexAccessToken = yandexClient.exchangeCodeForToken(code);

        // 2. Get user info from Yandex
        Map<String, Object> yandexUserInfo = yandexClient.getUserInfo(yandexAccessToken);

        String yandexId = String.valueOf(yandexUserInfo.get("id"));
        String email = (String) yandexUserInfo.get("default_email");
        String displayName = (String) yandexUserInfo.get("display_name");
        String firstName = (String) yandexUserInfo.get("first_name");
        String lastName = (String) yandexUserInfo.get("last_name");
        String avatarUrl = getYandexAvatarUrl(yandexUserInfo);

        if (displayName == null) {
            displayName = (firstName != null ? firstName : "") + (lastName != null ? " " + lastName : "");
            displayName = displayName.trim();
        }

        log.info("OAuth callback: provider=yandex, yandex_id={}, email={}", yandexId, email);

        return processOAuthCallback(PROVIDER_YANDEX, yandexId, email, displayName, avatarUrl,
                yandexUserInfo, ipAddress, userAgent);
    }

    // ======================== Common OAuth callback processing ========================

    /**
     * Common processing for all OAuth providers after user info is retrieved.
     * Handles: find/create OAuthIdentity, check links, return appropriate result.
     */
    private OAuthCallbackResult processOAuthCallback(
            String provider,
            String providerSub,
            String email,
            String displayName,
            String avatarUrl,
            Map<String, Object> rawAttributes,
            String ipAddress,
            String userAgent) {

        // 3. Find or create OAuthIdentity
        OAuthIdentity identity = oauthIdentityRepository.findByProviderAndProviderSub(provider, providerSub)
                .orElse(null);

        if (identity == null) {
            identity = OAuthIdentity.builder()
                    .provider(provider)
                    .providerSub(providerSub)
                    .email(email)
                    .name(displayName)
                    .avatarUrl(avatarUrl)
                    .rawAttributes(rawAttributes)
                    .build();
            identity = oauthIdentityRepository.save(identity);
            log.info("Created new OAuthIdentity: id={}, provider={}, email={}", identity.getId(), provider, email);
        } else {
            // 4. Update existing identity with fresh data
            identity.setEmail(email);
            identity.setName(displayName);
            identity.setAvatarUrl(avatarUrl);
            identity.setRawAttributes(rawAttributes);
            identity = oauthIdentityRepository.save(identity);
            log.debug("Updated OAuthIdentity: id={}, email={}", identity.getId(), email);
        }

        // 5. Find all active UserOAuthLinks for this identity
        List<UserOAuthLink> activeLinks = userOAuthLinkRepository.findActiveLinksWithUserAndTenant(identity.getId());

        OAuthCallbackResponse.OAuthUserInfo oauthUserInfo = OAuthCallbackResponse.OAuthUserInfo.builder()
                .email(email)
                .name(displayName)
                .avatarUrl(avatarUrl)
                .build();

        // 6. 0 links -> needs onboarding
        if (activeLinks.isEmpty()) {
            String oauthToken = generateOAuthIntermediateToken(identity);
            log.info("OAuth user needs onboarding: oauth_identity_id={}, email={}", identity.getId(), email);

            OAuthCallbackResponse response = OAuthCallbackResponse.builder()
                    .status("needs_onboarding")
                    .oauthToken(oauthToken)
                    .oauthTokenExpiresIn(600)
                    .oauthUser(oauthUserInfo)
                    .build();

            return OAuthCallbackResult.builder()
                    .response(response)
                    .rawRefreshToken(null)
                    .build();
        }

        // 7. 1+ links -> auto-login to first tenant (user switches via sidebar)
        UserOAuthLink link = activeLinks.get(0);
        User user = link.getUser();
        Tenant tenant = user.getTenant();

        MDC.put("tenant_id", tenant.getId().toString());
        MDC.put("user_id", user.getId().toString());

        log.info("OAuth auto-login: oauth_identity_id={}, tenant={}, total_tenants={}",
                identity.getId(), tenant.getSlug(), activeLinks.size());

        AuthResult<LoginResponse> loginResult = performOAuthLogin(user, tenant, ipAddress, userAgent, provider);

        OAuthCallbackResponse response = OAuthCallbackResponse.builder()
                .status("authenticated")
                .accessToken(loginResult.getResponse().getAccessToken())
                .tokenType("Bearer")
                .expiresIn((int) loginResult.getResponse().getExpiresIn())
                .user(loginResult.getResponse().getUser())
                .build();

        return OAuthCallbackResult.builder()
                .response(response)
                .rawRefreshToken(loginResult.getRawRefreshToken())
                .build();
    }

    // ======================== OAuth Login ========================

    /**
     * Perform OAuth login for a specific user/tenant combo. Generates tokens, updates last login, audits.
     *
     * @param user      the user to log in
     * @param tenant    the tenant context
     * @param ipAddress client IP address
     * @param userAgent client user agent
     * @param provider  OAuth provider name (for audit logging)
     */
    @Transactional
    public AuthResult<LoginResponse> performOAuthLogin(User user, Tenant tenant,
                                                        String ipAddress, String userAgent, String provider) {
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
        String tokenHash = AuthService.sha256(rawRefreshToken);

        long refreshTtl = calculateRefreshTokenTtl(user, tenant);

        RefreshToken refreshToken = RefreshToken.builder()
                .user(user)
                .tenantId(tenant.getId())
                .tokenHash(tokenHash)
                .deviceInfo(userAgent)
                .ipAddress(ipAddress)
                .expiresAt(Instant.now().plusSeconds(refreshTtl))
                .isRevoked(false)
                .build();
        refreshTokenRepository.save(refreshToken);

        userRepository.updateLastLoginTs(user.getId(), Instant.now());

        auditService.logAction(tenant.getId(), user.getId(), "OAUTH_LOGIN", "AUTH", user.getId(),
                Map.of("provider", provider, "roles", roles),
                ipAddress, userAgent, getCorrelationId());

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

    /**
     * Backward-compatible overload for existing callers (defaults to PROVIDER_YANDEX).
     */
    @Transactional
    public AuthResult<LoginResponse> performOAuthLogin(User user, Tenant tenant,
                                                        String ipAddress, String userAgent) {
        return performOAuthLogin(user, tenant, ipAddress, userAgent, PROVIDER_YANDEX);
    }

    // ======================== Token helpers ========================

    /**
     * Generate an intermediate JWT token for OAuth flow (onboarding or tenant selection).
     */
    public String generateOAuthIntermediateToken(OAuthIdentity identity) {
        return jwtTokenProvider.generateOAuthIntermediateToken(
                identity.getId(),
                identity.getEmail(),
                identity.getName(),
                identity.getProvider(),
                identity.getProviderSub()
        );
    }

    /**
     * Validate an OAuth intermediate token and return the associated OAuthIdentity.
     */
    public OAuthIdentity validateOAuthIntermediateToken(String token) {
        io.jsonwebtoken.Claims claims = jwtTokenProvider.validateOAuthIntermediateToken(token);
        UUID identityId = UUID.fromString(claims.getSubject());

        return oauthIdentityRepository.findById(identityId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "OAuth identity not found", "OAUTH_IDENTITY_NOT_FOUND"));
    }

    // ======================== Avatar helpers ========================

    /**
     * Extract avatar URL from Yandex user info.
     */
    public String getYandexAvatarUrl(Map<String, Object> yandexUserInfo) {
        Boolean isAvatarEmpty = (Boolean) yandexUserInfo.get("is_avatar_empty");
        if (Boolean.TRUE.equals(isAvatarEmpty)) {
            return null;
        }
        String defaultAvatarId = (String) yandexUserInfo.get("default_avatar_id");
        if (defaultAvatarId == null || defaultAvatarId.isBlank()) {
            return null;
        }
        return "https://avatars.yandex.net/get-yapic/" + defaultAvatarId + "/islands-200";
    }

    /**
     * @deprecated Use {@link #getYandexAvatarUrl(Map)} instead. Kept for backward compatibility.
     */
    @Deprecated
    public String getAvatarUrl(Map<String, Object> yandexUserInfo) {
        return getYandexAvatarUrl(yandexUserInfo);
    }

    // ======================== Utility ========================

    /**
     * Calculate refresh token TTL based on tenant and user settings.
     */
    public long calculateRefreshTokenTtl(User user, Tenant tenant) {
        int tenantMaxDays = 30; // default
        if (tenant.getSettings() != null && tenant.getSettings().containsKey("session_ttl_max_days")) {
            Object val = tenant.getSettings().get("session_ttl_max_days");
            if (val instanceof Number) {
                tenantMaxDays = ((Number) val).intValue();
            }
        }

        int userDays = tenantMaxDays; // default to tenant max
        if (user.getSettings() != null && user.getSettings().containsKey("session_ttl_days")) {
            Object val = user.getSettings().get("session_ttl_days");
            if (val instanceof Number) {
                userDays = ((Number) val).intValue();
            }
        }

        int effectiveDays = Math.min(userDays, tenantMaxDays);
        return (long) effectiveDays * 86400L;
    }

    private List<String> determineScopesForRoles(List<String> roles) {
        if (roles.contains("SUPER_ADMIN")) return List.of("global");
        if (roles.contains("OPERATOR")) return List.of("own");
        return List.of("tenant");
    }

    /**
     * Find all active OAuth links for a given OAuth identity.
     */
    public List<UserOAuthLink> findActiveLinksForIdentity(UUID oauthIdentityId) {
        return userOAuthLinkRepository.findActiveLinksWithUserAndTenant(oauthIdentityId);
    }

    /**
     * Validate the OAuth state parameter. Throws InvalidCredentialsException if invalid or expired.
     */
    private void validateState(String state) {
        if (!stateStore.validateAndConsume(state)) {
            log.warn("Invalid or expired OAuth state parameter");
            throw new InvalidCredentialsException(
                    "Invalid or expired OAuth state parameter", "OAUTH_STATE_INVALID");
        }
    }

    private String urlEncode(String value) {
        if (value == null) return "";
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private UUID getCorrelationId() {
        String cid = MDC.get("correlation_id");
        if (cid != null) {
            try { return UUID.fromString(cid); } catch (IllegalArgumentException e) { return null; }
        }
        return null;
    }

    /**
     * Internal result holder for OAuth callback that needs to return both
     * the API response and optionally the raw refresh token for cookie setting.
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class OAuthCallbackResult {
        private OAuthCallbackResponse response;
        private String rawRefreshToken;
    }
}
