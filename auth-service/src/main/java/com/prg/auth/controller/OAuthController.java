package com.prg.auth.controller;

import com.prg.auth.config.FrontendConfig;
import com.prg.auth.dto.request.OnboardingRequest;
import com.prg.auth.dto.request.SelectTenantRequest;
import com.prg.auth.dto.response.LoginResponse;
import com.prg.auth.dto.response.OAuthCallbackResponse;
import com.prg.auth.dto.response.OnboardingResponse;
import com.prg.auth.entity.OAuthIdentity;
import com.prg.auth.entity.Tenant;
import com.prg.auth.entity.User;
import com.prg.auth.entity.UserOAuthLink;
import com.prg.auth.exception.AccessDeniedException;
import com.prg.auth.exception.InvalidCredentialsException;
import com.prg.auth.service.AuthResult;
import com.prg.auth.service.OAuthRateLimiter;
import com.prg.auth.service.OAuthService;
import com.prg.auth.service.OnboardingService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/auth/oauth")
@RequiredArgsConstructor
public class OAuthController {

    private final OAuthService oauthService;
    private final OnboardingService onboardingService;
    private final FrontendConfig frontendConfig;
    private final OAuthRateLimiter oauthRateLimiter;

    private static final String REFRESH_TOKEN_COOKIE = "refresh_token";
    private static final String COOKIE_PATH = "/";

    // ======================== Yandex OAuth ========================

    /**
     * GET /api/v1/auth/oauth/yandex
     * Redirect to Yandex authorization page.
     */
    @GetMapping("/yandex")
    public ResponseEntity<Void> initiateYandexOAuth() {
        String authorizationUrl = oauthService.getAuthorizationUrl();
        log.info("Initiating Yandex OAuth redirect");
        return ResponseEntity.status(HttpStatus.FOUND)
                .header("Location", authorizationUrl)
                .build();
    }

    /**
     * GET /api/v1/auth/oauth/yandex/callback?code=...&state=...
     * Process Yandex callback. This is a browser redirect flow, not JSON API.
     */
    @GetMapping("/yandex/callback")
    public ResponseEntity<Void> handleYandexCallback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

        String ipAddress = getClientIp(httpRequest);
        oauthRateLimiter.checkCallbackRateLimit(ipAddress);

        return processOAuthCallback("yandex", code, state, error, httpRequest, httpResponse);
    }

    // ======================== Common OAuth callback processing ========================

    /**
     * Common controller-level processing for OAuth callbacks from any provider.
     */
    private ResponseEntity<Void> processOAuthCallback(
            String provider,
            String code,
            String state,
            String error,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

        String frontendBaseUrl = frontendConfig.getBaseUrl();

        // Handle error from provider
        if (error != null && !error.isBlank()) {
            log.warn("{} OAuth error: {}", provider, error);
            String redirectUrl = frontendBaseUrl + "/login?error=" + urlEncode("OAuth authorization denied");
            return ResponseEntity.status(HttpStatus.FOUND)
                    .header("Location", redirectUrl)
                    .build();
        }

        if (code == null || code.isBlank()) {
            log.warn("{} OAuth callback missing code parameter", provider);
            String redirectUrl = frontendBaseUrl + "/login?error=" + urlEncode("Missing authorization code");
            return ResponseEntity.status(HttpStatus.FOUND)
                    .header("Location", redirectUrl)
                    .build();
        }

        try {
            String ipAddress = getClientIp(httpRequest);
            String userAgent = httpRequest.getHeader("User-Agent");

            OAuthService.OAuthCallbackResult result = oauthService.handleCallback(code, state, ipAddress, userAgent);

            OAuthCallbackResponse response = result.getResponse();
            String redirectUrl;

            switch (response.getStatus()) {
                case "authenticated" -> {
                    // Single tenant - auto-login, set cookie and redirect to callback page
                    if (result.getRawRefreshToken() != null) {
                        // Default 30 days; actual token expiry was set in performOAuthLogin
                        setRefreshTokenCookie(httpResponse, result.getRawRefreshToken(), 30L * 86400L);
                    }
                    redirectUrl = frontendBaseUrl
                            + "/oauth/callback?access_token=" + urlEncode(response.getAccessToken())
                            + "&token_type=Bearer"
                            + "&expires_in=" + response.getExpiresIn();
                }
                case "needs_onboarding" -> {
                    // No tenants - redirect to callback page with onboarding params
                    var oauthUser = response.getOauthUser();
                    redirectUrl = frontendBaseUrl + "/oauth/callback"
                            + "?oauth_token=" + urlEncode(response.getOauthToken())
                            + "&status=needs_onboarding"
                            + "&name=" + urlEncode(oauthUser != null ? oauthUser.getName() : "")
                            + "&email=" + urlEncode(oauthUser != null ? oauthUser.getEmail() : "");
                }
                // tenant_selection_required removed: auto-login to first tenant, switch via sidebar
                default -> {
                    log.error("Unknown OAuth callback status: {}", response.getStatus());
                    redirectUrl = frontendBaseUrl + "/login?error=" + urlEncode("Unexpected error");
                }
            }

            log.info("OAuth callback processed: provider={}, status={}", provider, response.getStatus());
            return ResponseEntity.status(HttpStatus.FOUND)
                    .header("Location", redirectUrl)
                    .build();

        } catch (Exception e) {
            log.error("{} OAuth callback processing failed: {}", provider, e.getMessage(), e);
            String redirectUrl = frontendBaseUrl + "/login?error=" + urlEncode("Authentication failed");
            return ResponseEntity.status(HttpStatus.FOUND)
                    .header("Location", redirectUrl)
                    .build();
        }
    }

    // ======================== Onboarding & tenant selection ========================

    /**
     * POST /api/v1/auth/oauth/onboarding
     * Create first tenant for a new OAuth user.
     * Auth via oauth_token in Authorization header (not regular JWT).
     */
    @PostMapping("/onboarding")
    public ResponseEntity<OnboardingResponse> onboarding(
            @Valid @RequestBody OnboardingRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

        String ipAddress = getClientIp(httpRequest);
        oauthRateLimiter.checkOnboardingRateLimit(ipAddress);

        String oauthToken = extractBearerToken(httpRequest);
        if (oauthToken == null || oauthToken.isBlank()) {
            throw new InvalidCredentialsException(
                    "OAuth token is required", "OAUTH_TOKEN_MISSING");
        }

        String userAgent = httpRequest.getHeader("User-Agent");

        OnboardingService.OnboardingResult result = onboardingService.onboard(
                oauthToken, request, ipAddress, userAgent);

        if (result.getRawRefreshToken() != null) {
            // Use a default TTL of 30 days for newly onboarded users
            setRefreshTokenCookie(httpResponse, result.getRawRefreshToken(), 30L * 86400L);
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(result.getResponse());
    }

    /**
     * POST /api/v1/auth/oauth/select-tenant
     * Select a tenant after OAuth callback when user has 2+ tenants.
     * Auth via oauth_token in request body.
     */
    @PostMapping("/select-tenant")
    public ResponseEntity<LoginResponse> selectTenant(
            @Valid @RequestBody SelectTenantRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

        String oauthToken = request.getOauthToken();
        if (oauthToken == null || oauthToken.isBlank()) {
            // Try from header
            oauthToken = extractBearerToken(httpRequest);
        }
        if (oauthToken == null || oauthToken.isBlank()) {
            throw new InvalidCredentialsException(
                    "OAuth token is required", "OAUTH_TOKEN_MISSING");
        }

        String ipAddress = getClientIp(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");

        // Validate intermediate token and find identity
        OAuthIdentity identity = oauthService.validateOAuthIntermediateToken(oauthToken);

        // Find user in target tenant via OAuth links
        List<UserOAuthLink> activeLinks =
                oauthService.findActiveLinksForIdentity(identity.getId());

        UserOAuthLink targetLink = activeLinks.stream()
                .filter(link -> link.getUser().getTenant().getId().equals(request.getTenantId()))
                .findFirst()
                .orElseThrow(() -> new AccessDeniedException(
                        "You do not have access to the requested tenant"));

        User user = targetLink.getUser();
        Tenant tenant = user.getTenant();

        AuthResult<LoginResponse> loginResult =
                oauthService.performOAuthLogin(user, tenant, ipAddress, userAgent, identity.getProvider());

        long refreshTtl = oauthService.calculateRefreshTokenTtl(user, tenant);
        setRefreshTokenCookie(httpResponse, loginResult.getRawRefreshToken(), refreshTtl);

        return ResponseEntity.ok(loginResult.getResponse());
    }

    // --- Helper methods ---

    private String extractBearerToken(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        return null;
    }

    private void setRefreshTokenCookie(HttpServletResponse response, String token, long maxAge) {
        Cookie cookie = new Cookie(REFRESH_TOKEN_COOKIE, token);
        cookie.setHttpOnly(true);
        cookie.setSecure(true);
        cookie.setPath(COOKIE_PATH);
        cookie.setMaxAge((int) maxAge);
        cookie.setAttribute("SameSite", "Strict");
        response.addCookie(cookie);
    }

    private String getClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isEmpty()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private String urlEncode(String value) {
        if (value == null) return "";
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
