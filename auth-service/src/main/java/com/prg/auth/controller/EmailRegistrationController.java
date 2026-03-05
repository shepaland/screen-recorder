package com.prg.auth.controller;

import com.prg.auth.dto.request.InitiateOtpRequest;
import com.prg.auth.dto.request.ResendOtpRequest;
import com.prg.auth.dto.request.VerifyOtpRequest;
import com.prg.auth.dto.response.InitiateOtpResponse;
import com.prg.auth.dto.response.VerifyOtpResponse;
import com.prg.auth.service.EmailOtpService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;

@RestController
@RequestMapping("/api/v1/auth/register")
@RequiredArgsConstructor
@Slf4j
public class EmailRegistrationController {

    private final EmailOtpService emailOtpService;

    private static final String OTP_SESSION_COOKIE = "otp_session";
    private static final String REFRESH_TOKEN_COOKIE = "refresh_token";
    private static final String COOKIE_PATH = "/";

    /**
     * POST /api/v1/auth/register/initiate
     * Send OTP code to the specified email.
     */
    @PostMapping("/initiate")
    public ResponseEntity<InitiateOtpResponse> initiate(
            @Valid @RequestBody InitiateOtpRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

        String ipAddress = getClientIp(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");
        String otpSession = extractCookie(httpRequest, OTP_SESSION_COOKIE);

        // Generate OTP session if not present (BEFORE calling service, so fingerprint is computed correctly)
        if (otpSession == null || otpSession.isBlank()) {
            otpSession = emailOtpService.generateOtpSession();
        }

        InitiateOtpResponse response = emailOtpService.initiate(
                request.getEmail(), ipAddress, userAgent, otpSession);

        // Set/refresh OTP session cookie
        setOtpSessionCookie(httpResponse, otpSession);

        return ResponseEntity.ok(response);
    }

    /**
     * POST /api/v1/auth/register/verify
     * Verify OTP code and perform login or registration.
     */
    @PostMapping("/verify")
    public ResponseEntity<VerifyOtpResponse> verify(
            @Valid @RequestBody VerifyOtpRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

        String ipAddress = getClientIp(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");
        String otpSession = extractCookie(httpRequest, OTP_SESSION_COOKIE);

        EmailOtpService.VerifyOtpResult result = emailOtpService.verify(
                request.getCodeId(), request.getCode(), ipAddress, userAgent, otpSession);

        // Set refresh token cookie
        setRefreshTokenCookie(httpResponse, result.rawRefreshToken(), emailOtpService.getRefreshTokenTtl());

        // Clear OTP session cookie after successful verification
        clearCookie(httpResponse, OTP_SESSION_COOKIE);

        return ResponseEntity.ok(result.response());
    }

    /**
     * POST /api/v1/auth/register/resend
     * Resend OTP code. Invalidates previous code.
     */
    @PostMapping("/resend")
    public ResponseEntity<InitiateOtpResponse> resend(
            @Valid @RequestBody ResendOtpRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

        String ipAddress = getClientIp(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");
        String otpSession = extractCookie(httpRequest, OTP_SESSION_COOKIE);

        EmailOtpService.ResendOtpResult result = emailOtpService.resend(
                request.getCodeId(), ipAddress, userAgent, otpSession);

        return ResponseEntity.ok(result.response());
    }

    // --- Cookie helpers ---

    private String extractCookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) return null;
        return Arrays.stream(request.getCookies())
                .filter(c -> name.equals(c.getName()))
                .findFirst()
                .map(Cookie::getValue)
                .orElse(null);
    }

    private void setOtpSessionCookie(HttpServletResponse response, String value) {
        Cookie cookie = new Cookie(OTP_SESSION_COOKIE, value);
        cookie.setHttpOnly(true);
        cookie.setSecure(true);
        cookie.setPath(COOKIE_PATH);
        cookie.setMaxAge(1800); // 30 minutes
        cookie.setAttribute("SameSite", "Strict");
        response.addCookie(cookie);
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

    private void clearCookie(HttpServletResponse response, String name) {
        Cookie cookie = new Cookie(name, "");
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
