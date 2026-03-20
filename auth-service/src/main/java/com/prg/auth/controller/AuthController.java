package com.prg.auth.controller;

import com.prg.auth.dto.request.LoginRequest;
import com.prg.auth.dto.request.SelectTenantRequest;
import com.prg.auth.dto.response.LoginResponse;
import com.prg.auth.dto.response.MyTenantsResponse;
import com.prg.auth.dto.response.TokenResponse;
import com.prg.auth.entity.Invitation;
import com.prg.auth.entity.User;
import com.prg.auth.repository.InvitationRepository;
import com.prg.auth.security.UserPrincipal;
import com.prg.auth.service.AuthResult;
import com.prg.auth.service.AuthService;
import com.prg.auth.service.InvitationService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final AuthService authService;
    private final InvitationService invitationService;
    private final InvitationRepository invitationRepository;

    private static final String REFRESH_TOKEN_COOKIE = "refresh_token";
    private static final String COOKIE_PATH = "/";

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                                HttpServletRequest httpRequest,
                                                HttpServletResponse httpResponse) {
        String ipAddress = getClientIp(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");

        AuthResult<LoginResponse> result = authService.login(request, ipAddress, userAgent);

        setRefreshTokenCookie(httpResponse, result.getRawRefreshToken(), authService.getRefreshTokenTtl());

        return ResponseEntity.ok(result.getResponse());
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(HttpServletRequest httpRequest,
                                                  HttpServletResponse httpResponse) {
        String rawRefreshToken = extractRefreshTokenFromCookie(httpRequest);
        String ipAddress = getClientIp(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");

        AuthResult<TokenResponse> result = authService.refresh(rawRefreshToken, ipAddress, userAgent);

        setRefreshTokenCookie(httpResponse, result.getRawRefreshToken(), authService.getRefreshTokenTtl());

        return ResponseEntity.ok(result.getResponse());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal UserPrincipal principal,
                                        HttpServletRequest httpRequest,
                                        HttpServletResponse httpResponse) {
        String rawRefreshToken = extractRefreshTokenFromCookie(httpRequest);
        String ipAddress = getClientIp(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");

        authService.logout(rawRefreshToken, principal.getUserId(), principal.getTenantId(), ipAddress, userAgent);
        clearRefreshTokenCookie(httpResponse);

        return ResponseEntity.noContent().build();
    }

    @PostMapping("/logout-all")
    public ResponseEntity<Void> logoutAll(@AuthenticationPrincipal UserPrincipal principal,
                                           HttpServletRequest httpRequest,
                                           HttpServletResponse httpResponse) {
        String ipAddress = getClientIp(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");

        authService.logoutAll(principal.getUserId(), principal.getTenantId(), ipAddress, userAgent);
        clearRefreshTokenCookie(httpResponse);

        return ResponseEntity.noContent().build();
    }

    /**
     * GET /api/v1/auth/my-tenants
     * List all tenants accessible to the current user (via OAuth identity links).
     */
    @GetMapping("/my-tenants")
    public ResponseEntity<MyTenantsResponse> getMyTenants(@AuthenticationPrincipal UserPrincipal principal) {
        MyTenantsResponse response = authService.getMyTenants(principal.getUserId());
        return ResponseEntity.ok(response);
    }

    /**
     * POST /api/v1/auth/switch-tenant
     * Switch the authenticated user to a different tenant.
     * Requires OAuth identity linked to a user in the target tenant.
     */
    @PostMapping("/switch-tenant")
    public ResponseEntity<LoginResponse> switchTenant(
            @Valid @RequestBody SelectTenantRequest request,
            @AuthenticationPrincipal UserPrincipal principal,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

        String ipAddress = getClientIp(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");

        AuthResult<LoginResponse> result = authService.switchTenant(
                principal.getUserId(), request.getTenantId(), ipAddress, userAgent);

        setRefreshTokenCookie(httpResponse, result.getRawRefreshToken(), authService.getRefreshTokenTtl());

        return ResponseEntity.ok(result.getResponse());
    }

    /**
     * GET /api/v1/auth/invite/{token} — check invitation validity (public).
     */
    @GetMapping("/invite/{token}")
    public ResponseEntity<?> getInvitation(@PathVariable String token) {
        Invitation invitation = invitationRepository.findByToken(token)
                .orElseThrow(() -> new com.prg.auth.exception.ResourceNotFoundException(
                        "Invitation not found", "INVITATION_NOT_FOUND"));

        if (invitation.isAccepted()) {
            return ResponseEntity.badRequest().body(java.util.Map.of(
                    "error", "Приглашение уже использовано", "code", "INVITATION_ACCEPTED"));
        }
        if (invitation.isExpired()) {
            return ResponseEntity.badRequest().body(java.util.Map.of(
                    "error", "Приглашение истекло", "code", "INVITATION_EXPIRED"));
        }

        return ResponseEntity.ok(java.util.Map.of(
                "email", invitation.getEmail(),
                "tenant_id", invitation.getTenantId(),
                "expires_at", invitation.getExpiresAt().toString()));
    }

    /**
     * POST /api/v1/auth/invite/{token}/accept — accept invitation (public).
     */
    @PostMapping("/invite/{token}/accept")
    public ResponseEntity<LoginResponse> acceptInvitation(
            @PathVariable String token,
            @RequestBody java.util.Map<String, String> request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

        String password = request.get("password");
        String firstName = request.get("first_name");
        String lastName = request.get("last_name");

        User user = invitationService.acceptInvitation(token, password, firstName, lastName);

        // Auto-login after accepting invitation
        LoginRequest loginReq = new LoginRequest();
        loginReq.setUsername(user.getEmail());
        loginReq.setPassword(password);

        String ipAddress = getClientIp(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");

        AuthResult<LoginResponse> result = authService.login(loginReq, ipAddress, userAgent);
        setRefreshTokenCookie(httpResponse, result.getRawRefreshToken(), authService.getRefreshTokenTtl());

        return ResponseEntity.ok(result.getResponse());
    }

    private String extractRefreshTokenFromCookie(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        return Arrays.stream(request.getCookies())
                .filter(c -> REFRESH_TOKEN_COOKIE.equals(c.getName()))
                .findFirst()
                .map(Cookie::getValue)
                .orElse(null);
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

    private void clearRefreshTokenCookie(HttpServletResponse response) {
        Cookie cookie = new Cookie(REFRESH_TOKEN_COOKIE, "");
        cookie.setHttpOnly(true);
        cookie.setSecure(true);
        cookie.setPath(COOKIE_PATH);
        cookie.setMaxAge(0);
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
}
