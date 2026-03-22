package com.prg.auth.controller;

import com.prg.auth.dto.request.LoginRequest;
import com.prg.auth.dto.request.RegisterRequest;
import com.prg.auth.dto.response.LoginResponse;
import com.prg.auth.entity.User;
import com.prg.auth.service.AuthResult;
import com.prg.auth.service.AuthService;
import com.prg.auth.service.EmailRegistrationService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Slf4j
public class RegisterController {

    private final EmailRegistrationService registrationService;
    private final AuthService authService;

    private static final String REFRESH_TOKEN_COOKIE = "refresh_token";
    private static final String COOKIE_PATH = "/";

    @Value("${prg.frontend.base-url:http://localhost:3000}")
    private String frontendBaseUrl;

    /**
     * POST /api/v1/auth/register — register a new user via email wizard.
     */
    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(
            @Valid @RequestBody RegisterRequest request,
            HttpServletRequest httpRequest) {

        String ipAddress = getClientIp(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");

        Map<String, Object> result = registrationService.register(request, ipAddress, userAgent);
        return ResponseEntity.ok(result);
    }

    /**
     * GET /api/v1/auth/verify-email/{token} — verify email by clicking the link.
     * Redirects to frontend with JWT token on success.
     */
    @GetMapping("/verify-email/{token}")
    public ResponseEntity<Void> verifyEmail(
            @PathVariable String token,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

        try {
            User user = registrationService.verifyEmail(token);

            // Auto-login: generate JWT tokens
            LoginRequest loginReq = new LoginRequest();
            loginReq.setUsername(user.getEmail());
            // Use internal login that skips password check
            String ipAddress = getClientIp(httpRequest);
            String userAgent = httpRequest.getHeader("User-Agent");

            AuthResult<LoginResponse> authResult = authService.loginVerifiedUser(user, ipAddress, userAgent);

            // Set refresh token cookie
            Cookie cookie = new Cookie(REFRESH_TOKEN_COOKIE, authResult.getRawRefreshToken());
            cookie.setHttpOnly(true);
            cookie.setSecure(true);
            cookie.setPath(COOKIE_PATH);
            cookie.setMaxAge((int) authService.getRefreshTokenTtl());
            cookie.setAttribute("SameSite", "Strict");
            httpResponse.addCookie(cookie);

            // Redirect to frontend with access token
            String redirectUrl = frontendBaseUrl + "/?verified=true&token="
                    + URLEncoder.encode(authResult.getResponse().getAccessToken(), StandardCharsets.UTF_8);

            return ResponseEntity.status(HttpStatus.FOUND)
                    .header("Location", redirectUrl)
                    .build();
        } catch (Exception e) {
            log.warn("Email verification failed: {}", e.getMessage());
            String redirectUrl = frontendBaseUrl + "/verify-email-expired";
            return ResponseEntity.status(HttpStatus.FOUND)
                    .header("Location", redirectUrl)
                    .build();
        }
    }

    /**
     * POST /api/v1/auth/register/resend-verification — resend verification email.
     */
    @PostMapping("/register/resend-verification")
    public ResponseEntity<Map<String, Object>> resendVerification(
            @RequestBody Map<String, String> request,
            HttpServletRequest httpRequest) {

        String email = request.get("email");
        if (email == null || email.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Email is required"));
        }

        String ipAddress = getClientIp(httpRequest);
        Map<String, Object> result = registrationService.resendVerification(email, ipAddress);
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
