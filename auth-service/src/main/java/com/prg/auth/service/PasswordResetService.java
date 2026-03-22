package com.prg.auth.service;

import com.prg.auth.entity.PasswordResetToken;
import com.prg.auth.entity.User;
import com.prg.auth.exception.BadRequestException;
import com.prg.auth.exception.RateLimitExceededException;
import com.prg.auth.repository.PasswordResetTokenRepository;
import com.prg.auth.repository.RefreshTokenRepository;
import com.prg.auth.repository.UserRepository;
import com.prg.auth.util.PasswordValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class PasswordResetService {

    private static final long RESET_TOKEN_TTL = 3600; // 1 hour
    private static final int RESEND_COOLDOWN = 60;
    private static final int MAX_RESETS_PER_HOUR = 5;

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository resetTokenRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final AuditService auditService;

    private final ConcurrentHashMap<String, List<Long>> ipAttempts = new ConcurrentHashMap<>();

    @Value("${prg.frontend.base-url:http://localhost:3000}")
    private String frontendBaseUrl;

    /**
     * Request password reset — sends email with reset link.
     * Always returns success (enumeration protection).
     */
    @Transactional
    public Map<String, Object> forgotPassword(String rawEmail, String ipAddress, String userAgent) {
        checkIpRateLimit(ipAddress);

        String email = normalizeEmail(rawEmail);
        User user = userRepository.findActiveByEmail(email).orElse(null);

        // Always return same response (enumeration protection)
        Map<String, Object> response = Map.of(
                "message", "If an account with this email exists, a password reset link has been sent"
        );

        if (user == null) {
            recordIpAttempt(ipAddress);
            return response;
        }

        // Can't reset password for users who never set one (pure OAuth)
        if (!Boolean.TRUE.equals(user.getIsPasswordSet()) && "oauth".equals(user.getAuthProvider())) {
            recordIpAttempt(ipAddress);
            return response;
        }

        // Check rate limit per user
        Instant hourAgo = Instant.now().minusSeconds(3600);
        long recentCount = resetTokenRepository.countByUserIdSince(user.getId(), hourAgo);
        if (recentCount >= MAX_RESETS_PER_HOUR) {
            recordIpAttempt(ipAddress);
            return response; // silent, no error
        }

        // Invalidate previous tokens
        resetTokenRepository.invalidateAllByUserId(user.getId());

        // Generate reset token
        String token = UUID.randomUUID().toString();
        PasswordResetToken resetToken = PasswordResetToken.builder()
                .userId(user.getId())
                .token(token)
                .isUsed(false)
                .expiresAt(Instant.now().plusSeconds(RESET_TOKEN_TTL))
                .build();
        resetTokenRepository.save(resetToken);

        // Send email
        String resetLink = frontendBaseUrl + "/reset-password/" + token;
        try {
            emailService.sendPasswordResetLink(email, user.getFirstName(), resetLink);
        } catch (Exception e) {
            log.error("Failed to send password reset email to {}: {}", maskEmail(email), e.getMessage());
            // Don't throw — return same response
            return response;
        }

        recordIpAttempt(ipAddress);

        // Audit
        try {
            auditService.logAction(user.getTenant().getId(), user.getId(), "PASSWORD_RESET_REQUESTED", "AUTH",
                    user.getId(), Map.of("email", maskEmail(email)), ipAddress, userAgent, null);
        } catch (Exception e) {
            log.warn("Failed to audit PASSWORD_RESET_REQUESTED: {}", e.getMessage());
        }

        log.info("Password reset requested for: {}", maskEmail(email));
        return response;
    }

    /**
     * Reset password using token.
     */
    @Transactional
    public User resetPassword(String token, String newPassword, String ipAddress, String userAgent) {
        PasswordResetToken resetToken = resetTokenRepository.findByToken(token)
                .orElseThrow(() -> new BadRequestException("Reset link is invalid", "TOKEN_NOT_FOUND"));

        if (resetToken.getIsUsed()) {
            throw new BadRequestException("This reset link has already been used", "TOKEN_ALREADY_USED");
        }

        if (resetToken.getExpiresAt().isBefore(Instant.now())) {
            throw new BadRequestException("Reset link has expired", "TOKEN_EXPIRED");
        }

        // Validate password
        String passwordError = PasswordValidator.validate(newPassword);
        if (passwordError != null) {
            throw new BadRequestException(passwordError, "WEAK_PASSWORD");
        }

        // Mark token as used
        resetToken.setIsUsed(true);
        resetTokenRepository.save(resetToken);

        // Update password (findById first for update, then re-fetch with roles for auto-login)
        User user = userRepository.findById(resetToken.getUserId())
                .orElseThrow(() -> new BadRequestException("User not found", "USER_NOT_FOUND"));

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setIsPasswordSet(true);
        userRepository.save(user);

        // Invalidate all refresh tokens (force re-login on all devices)
        refreshTokenRepository.revokeAllByUserId(user.getId());

        // Re-fetch with roles eagerly loaded (for auto-login)
        user = userRepository.findActiveByEmail(user.getEmail())
                .orElseThrow(() -> new BadRequestException("User not found", "USER_NOT_FOUND"));

        // Audit
        try {
            auditService.logAction(user.getTenant().getId(), user.getId(), "PASSWORD_RESET_COMPLETED", "AUTH",
                    user.getId(), null, ipAddress, userAgent, null);
        } catch (Exception e) {
            log.warn("Failed to audit PASSWORD_RESET_COMPLETED: {}", e.getMessage());
        }

        log.info("Password reset completed for user_id={}", user.getId());
        return user;
    }

    /**
     * Resend password reset link.
     */
    @Transactional
    public Map<String, Object> resendResetLink(String rawEmail, String ipAddress) {
        checkIpRateLimit(ipAddress);
        // Delegate to forgotPassword (same logic)
        return forgotPassword(rawEmail, ipAddress, null);
    }

    @Scheduled(fixedRate = 86400000)
    @Transactional
    public void cleanupExpiredTokens() {
        int deleted = resetTokenRepository.deleteExpiredBefore(Instant.now().minusSeconds(172800));
        if (deleted > 0) {
            log.info("Cleaned up {} expired password reset tokens", deleted);
        }
    }

    // --- Helpers ---

    private String normalizeEmail(String email) {
        email = email.trim().toLowerCase();
        String[] parts = email.split("@", 2);
        if (parts.length != 2) return email;
        String local = parts[0];
        String domain = parts[1];
        int plusIdx = local.indexOf('+');
        if (plusIdx > 0) local = local.substring(0, plusIdx);
        if ("gmail.com".equals(domain) || "googlemail.com".equals(domain)) {
            local = local.replace(".", "");
            domain = "gmail.com";
        }
        return local + "@" + domain;
    }

    private void checkIpRateLimit(String ipAddress) {
        List<Long> attempts = ipAttempts.get(ipAddress);
        if (attempts == null) return;
        long windowStart = System.currentTimeMillis() - 300000;
        long recentAttempts = attempts.stream().filter(ts -> ts > windowStart).count();
        if (recentAttempts >= 10) {
            throw new RateLimitExceededException("Too many requests. Please try again later.", "TOO_MANY_REQUESTS");
        }
    }

    private void recordIpAttempt(String ipAddress) {
        ipAttempts.computeIfAbsent(ipAddress, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(System.currentTimeMillis());
    }

    @Scheduled(fixedRate = 300000)
    public void cleanupIpRateLimit() {
        long cutoff = System.currentTimeMillis() - 300000;
        ipAttempts.forEach((ip, attempts) -> {
            attempts.removeIf(ts -> ts < cutoff);
            if (attempts.isEmpty()) ipAttempts.remove(ip);
        });
    }

    private String maskEmail(String email) {
        int atIdx = email.indexOf('@');
        if (atIdx <= 2) return "***" + email.substring(atIdx);
        return email.substring(0, 2) + "***" + email.substring(atIdx);
    }
}
