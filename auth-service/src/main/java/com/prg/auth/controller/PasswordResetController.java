package com.prg.auth.controller;

import com.prg.auth.dto.response.LoginResponse;
import com.prg.auth.entity.User;
import com.prg.auth.service.AuthResult;
import com.prg.auth.service.AuthService;
import com.prg.auth.service.PasswordResetService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Slf4j
public class PasswordResetController {

    private final PasswordResetService resetService;
    private final AuthService authService;

    private static final String REFRESH_TOKEN_COOKIE = "refresh_token";
    private static final String COOKIE_PATH = "/";

    /**
     * POST /api/v1/auth/forgot-password — request password reset link.
     */
    @PostMapping("/forgot-password")
    public ResponseEntity<Map<String, Object>> forgotPassword(
            @RequestBody Map<String, String> request,
            HttpServletRequest httpRequest) {

        String email = request.get("email");
        if (email == null || email.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Email is required"));
        }

        String ipAddress = getClientIp(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");

        Map<String, Object> result = resetService.forgotPassword(email, ipAddress, userAgent);
        return ResponseEntity.ok(result);
    }

    /**
     * POST /api/v1/auth/reset-password — set new password using reset token.
     */
    @PostMapping("/reset-password")
    public ResponseEntity<LoginResponse> resetPassword(
            @RequestBody Map<String, String> request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

        String token = request.get("token");
        String password = request.get("password");

        if (token == null || token.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        if (password == null || password.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        String ipAddress = getClientIp(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");

        User user = resetService.resetPassword(token, password, ipAddress, userAgent);

        // Auto-login
        AuthResult<LoginResponse> authResult = authService.loginVerifiedUser(user, ipAddress, userAgent);

        // Set refresh token cookie
        Cookie cookie = new Cookie(REFRESH_TOKEN_COOKIE, authResult.getRawRefreshToken());
        cookie.setHttpOnly(true);
        cookie.setSecure(true);
        cookie.setPath(COOKIE_PATH);
        cookie.setMaxAge((int) authService.getRefreshTokenTtl());
        cookie.setAttribute("SameSite", "Strict");
        httpResponse.addCookie(cookie);

        return ResponseEntity.ok(authResult.getResponse());
    }

    /**
     * POST /api/v1/auth/forgot-password/resend — resend reset link.
     */
    @PostMapping("/forgot-password/resend")
    public ResponseEntity<Map<String, Object>> resendResetLink(
            @RequestBody Map<String, String> request,
            HttpServletRequest httpRequest) {

        String email = request.get("email");
        if (email == null || email.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Email is required"));
        }

        String ipAddress = getClientIp(httpRequest);
        Map<String, Object> result = resetService.resendResetLink(email, ipAddress);
        return ResponseEntity.ok(result);
    }

    private String getClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
