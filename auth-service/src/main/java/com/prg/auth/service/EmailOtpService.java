package com.prg.auth.service;

import com.prg.auth.config.EmailOtpConfig;
import com.prg.auth.config.JwtConfig;
import com.prg.auth.dto.response.*;
import com.prg.auth.entity.*;
import com.prg.auth.exception.AccessDeniedException;
import com.prg.auth.exception.BadRequestException;
import com.prg.auth.exception.InvalidCredentialsException;
import com.prg.auth.exception.RateLimitExceededException;
import com.prg.auth.exception.ResourceNotFoundException;
import com.prg.auth.repository.*;
import com.prg.auth.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailOtpService {

    private static final UUID TEMPLATE_TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");

    private final EmailVerificationCodeRepository codeRepository;
    private final DisposableEmailDomainRepository disposableDomainRepository;
    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final RoleRepository roleRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final JwtConfig jwtConfig;
    private final EmailOtpConfig otpConfig;
    private final EmailService emailService;
    private final AuditService auditService;
    private final PasswordEncoder passwordEncoder;

    private final SecureRandom secureRandom = new SecureRandom();

    // In-memory per-IP rate limiting (same pattern as AuthService)
    private final ConcurrentHashMap<String, List<Long>> ipAttempts = new ConcurrentHashMap<>();

    // --- Public API ---

    /**
     * Initiate email OTP verification: validate, generate code, send email.
     */
    @Transactional
    public InitiateOtpResponse initiate(String rawEmail, String ipAddress, String userAgent,
                                         String otpSessionCookie) {
        // 1. Normalize email
        String email = normalizeEmail(rawEmail);

        // 2. Extract domain and check disposable (timing-safe: always run query)
        String domain = email.substring(email.indexOf('@') + 1).toLowerCase();
        long disposableCount = disposableDomainRepository.countByDomain(domain);
        if (disposableCount > 0) {
            throw new BadRequestException("Registration with disposable email addresses is not allowed", "DISPOSABLE_EMAIL");
        }

        // 3. Per-IP rate limit
        checkIpRateLimit(ipAddress);

        // 4. Per-email cooldown (60 seconds between sends)
        Optional<EmailVerificationCode> latestCode = codeRepository.findLatestByEmail(email);
        if (latestCode.isPresent()) {
            long secondsSinceLastSend = Instant.now().getEpochSecond() - latestCode.get().getCreatedTs().getEpochSecond();
            if (secondsSinceLastSend < otpConfig.getSendCooldown()) {
                int retryAfter = otpConfig.getSendCooldown() - (int) secondsSinceLastSend;
                throw new RateLimitExceededException(
                        "Please wait before requesting a new code", "OTP_SEND_RATE_LIMITED");
            }
        }

        // 5. Per-email window rate limit (max sends in window)
        Instant windowStart = Instant.now().minusSeconds(otpConfig.getSendWindowDuration());
        long sendCount = codeRepository.countByEmailSince(email, windowStart);
        if (sendCount >= otpConfig.getSendWindowMax()) {
            throw new RateLimitExceededException(
                    "Too many code requests. Please try again later.", "OTP_SEND_LIMIT");
        }

        // 6. Generate 6-digit code
        int codeInt = secureRandom.nextInt(100000, 1000000);
        String code = String.valueOf(codeInt);

        // 7. Compute fingerprint and code hash (otpSessionCookie is guaranteed non-null by controller)
        String fingerprint = sha256(otpSessionCookie + ":" + (userAgent != null ? userAgent : ""));
        String codeHash = hmacSha256(code, otpConfig.getHmacSecret());

        // 8. Invalidate all previous unused codes for this email
        codeRepository.invalidateAllByEmail(email);

        // 9. Save new code
        EmailVerificationCode verificationCode = EmailVerificationCode.builder()
                .email(email)
                .codeHash(codeHash)
                .purpose("register")
                .fingerprint(fingerprint)
                .attempts(0)
                .maxAttempts(otpConfig.getMaxVerifyAttempts())
                .isUsed(false)
                .isBlocked(false)
                .expiresAt(Instant.now().plusSeconds(otpConfig.getCodeTtl()))
                .build();
        verificationCode = codeRepository.save(verificationCode);

        // 10. Send email
        emailService.sendVerificationCode(email, code);

        // 11. Record IP attempt
        recordIpAttempt(ipAddress);

        // 12. Audit (use system tenant for pre-auth events since audit_log.tenant_id is NOT NULL)
        try {
            auditService.logAction(TEMPLATE_TENANT_ID, null, "OTP_SENT", "AUTH", null,
                    Map.of("email", maskEmail(email)), ipAddress, userAgent, getCorrelationId());
        } catch (Exception e) {
            log.warn("Failed to audit OTP_SENT: {}", e.getMessage());
        }

        log.info("OTP initiated for email: {}", maskEmail(email));

        return InitiateOtpResponse.builder()
                .message("Verification code sent")
                .codeId(verificationCode.getId())
                .expiresIn(otpConfig.getCodeTtl())
                .resendAvailableIn(otpConfig.getSendCooldown())
                .build();
    }

    /**
     * Get the OTP session value for setting as cookie (used by controller).
     */
    public String generateOtpSession() {
        return UUID.randomUUID().toString();
    }

    /**
     * Verify OTP code and perform login or registration.
     */
    @Transactional
    public VerifyOtpResult verify(UUID codeId, String code, String ipAddress, String userAgent,
                                   String otpSessionCookie) {
        // 1. Find code with SELECT FOR UPDATE (prevents race condition)
        EmailVerificationCode verificationCode = codeRepository.findByIdForUpdate(codeId)
                .orElseThrow(() -> new ResourceNotFoundException("Verification code not found", "OTP_NOT_FOUND"));

        // 2. Check is_used
        if (verificationCode.getIsUsed()) {
            throw new BadRequestException("This code has already been used", "OTP_ALREADY_USED");
        }

        // 3. Check expiration
        if (verificationCode.getExpiresAt().isBefore(Instant.now())) {
            throw new BadRequestException("Verification code has expired", "OTP_EXPIRED");
        }

        // 4. Check blocking
        if (verificationCode.getIsBlocked()) {
            if (verificationCode.getBlockedUntil() != null && verificationCode.getBlockedUntil().isAfter(Instant.now())) {
                throw new RateLimitExceededException(
                        "Too many attempts. Try again in 30 minutes.", "OTP_VERIFY_BLOCKED");
            }
            // Block period passed, but code is still marked blocked - treat as blocked
            throw new RateLimitExceededException(
                    "Too many attempts. Please request a new code.", "OTP_VERIFY_BLOCKED");
        }

        // 5. Compute and compare fingerprint (timing-safe)
        String fingerprint = sha256((otpSessionCookie != null ? otpSessionCookie : "") + ":" + (userAgent != null ? userAgent : ""));
        if (!MessageDigest.isEqual(
                fingerprint.getBytes(StandardCharsets.UTF_8),
                verificationCode.getFingerprint().getBytes(StandardCharsets.UTF_8))) {
            throw new AccessDeniedException(
                    "Code must be verified from the same browser session", "OTP_FINGERPRINT_MISMATCH");
        }

        // 6. Increment attempts
        verificationCode.setAttempts(verificationCode.getAttempts() + 1);

        // 7. Compare code hash (timing-safe)
        String computedHash = hmacSha256(code, otpConfig.getHmacSecret());
        boolean codeMatches = MessageDigest.isEqual(
                computedHash.getBytes(StandardCharsets.UTF_8),
                verificationCode.getCodeHash().getBytes(StandardCharsets.UTF_8));

        if (!codeMatches) {
            // Check if max attempts reached
            if (verificationCode.getAttempts() >= verificationCode.getMaxAttempts()) {
                verificationCode.setIsBlocked(true);
                verificationCode.setBlockedUntil(Instant.now().plusSeconds(otpConfig.getBlockDuration()));
                codeRepository.save(verificationCode);

                try {
                    auditService.logAction(TEMPLATE_TENANT_ID, null, "OTP_VERIFY_BLOCKED", "AUTH", null,
                            Map.of("email", maskEmail(verificationCode.getEmail()),
                                    "attempts", verificationCode.getAttempts()),
                            ipAddress, userAgent, getCorrelationId());
                } catch (Exception e) {
                    log.warn("Failed to audit OTP_VERIFY_BLOCKED: {}", e.getMessage());
                }

                throw new RateLimitExceededException(
                        "Too many attempts. Try again in 30 minutes.", "OTP_VERIFY_BLOCKED");
            }
            codeRepository.save(verificationCode);
            throw new BadRequestException("Invalid verification code", "OTP_INVALID");
        }

        // 8. Code is valid - mark as used
        verificationCode.setIsUsed(true);
        codeRepository.save(verificationCode);

        String email = verificationCode.getEmail();

        // 9. Look up existing users by email
        List<User> existingUsers = userRepository.findActiveUsersByEmail(email);

        boolean isNewUser;
        User user;
        Tenant tenant;

        if (!existingUsers.isEmpty()) {
            // Login flow - existing user
            user = existingUsers.get(0);

            // OTP login policy: only allow for email/oauth auth_provider
            if ("password".equals(user.getAuthProvider())) {
                throw new BadRequestException(
                        "For this account, please use password login", "OTP_LOGIN_NOT_ALLOWED");
            }

            tenant = user.getTenant();
            isNewUser = false;

            // Update email_verified if not already set
            if (!Boolean.TRUE.equals(user.getEmailVerified())) {
                user.setEmailVerified(true);
                userRepository.save(user);
            }

            userRepository.updateLastLoginTs(user.getId(), Instant.now());

            auditService.logAction(tenant.getId(), user.getId(), "EMAIL_LOGIN", "AUTH", user.getId(),
                    Map.of("email", maskEmail(email)), ipAddress, userAgent, getCorrelationId());

            log.info("OTP login: user_id={}, email={}", user.getId(), maskEmail(email));
        } else {
            // Registration flow - new user

            // Check tenant limit per email (existingUsers is already empty here, but for future multi-tenant registration)
            if (existingUsers.size() >= otpConfig.getMaxTenantsPerEmail()) {
                throw new BadRequestException(
                        "Maximum number of accounts for this email reached", "TENANT_LIMIT_REACHED");
            }

            // Create tenant
            String slug = generateSlug();
            Map<String, Object> tenantSettings = new HashMap<>();
            tenantSettings.put("session_ttl_max_days", 30);

            tenant = Tenant.builder()
                    .name("Моя компания")
                    .slug(slug)
                    .isActive(true)
                    .settings(tenantSettings)
                    .build();
            tenant = tenantRepository.save(tenant);

            MDC.put("tenant_id", tenant.getId().toString());

            // Copy system roles from template tenant
            List<Role> templateRoles = roleRepository.findByTenantIdWithPermissions(TEMPLATE_TENANT_ID);
            Map<String, Role> newRoles = new HashMap<>();

            for (Role templateRole : templateRoles) {
                Role newRole = Role.builder()
                        .tenant(tenant)
                        .code(templateRole.getCode())
                        .name(templateRole.getName())
                        .description(templateRole.getDescription())
                        .isSystem(true)
                        .permissions(new HashSet<>(templateRole.getPermissions()))
                        .build();
                newRole = roleRepository.save(newRole);
                newRoles.put(newRole.getCode(), newRole);
            }

            // Create user
            user = User.builder()
                    .tenant(tenant)
                    .username(email)
                    .email(email)
                    .passwordHash(null)
                    .authProvider("email")
                    .emailVerified(true)
                    .isPasswordSet(false)
                    .firstName(null)
                    .lastName(null)
                    .isActive(true)
                    .settings(new HashMap<>())
                    .build();

            // Assign OWNER role
            Role ownerRole = newRoles.get("OWNER");
            if (ownerRole == null) {
                ownerRole = newRoles.get("TENANT_ADMIN");
            }
            if (ownerRole != null) {
                user.setRoles(Set.of(ownerRole));
            }

            user = userRepository.save(user);
            isNewUser = true;

            MDC.put("user_id", user.getId().toString());

            auditService.logAction(tenant.getId(), user.getId(), "EMAIL_REGISTER", "AUTH", user.getId(),
                    Map.of("email", maskEmail(email), "tenant_slug", slug),
                    ipAddress, userAgent, getCorrelationId());

            log.info("OTP registration: user_id={}, tenant_id={}, email={}",
                    user.getId(), tenant.getId(), maskEmail(email));
        }

        // 10. Generate JWT tokens
        List<String> roles = user.getRoles().stream().map(Role::getCode).toList();
        List<String> permissions = user.getRoles().stream()
                .flatMap(r -> r.getPermissions().stream())
                .map(Permission::getCode)
                .distinct().sorted().toList();
        List<String> scopes = determineScopesForRoles(roles);

        String accessToken = jwtTokenProvider.generateAccessToken(
                user.getId(), tenant.getId(), user.getUsername(), user.getEmail(),
                roles, permissions, scopes);

        String rawRefreshToken = UUID.randomUUID().toString();
        String tokenHash = AuthService.sha256(rawRefreshToken);

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

        // Build response
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

        VerifyOtpResponse response = VerifyOtpResponse.builder()
                .accessToken(accessToken)
                .tokenType("Bearer")
                .expiresIn(jwtTokenProvider.getAccessTokenTtl())
                .user(userResponse)
                .isNewUser(isNewUser)
                .build();

        return new VerifyOtpResult(response, rawRefreshToken);
    }

    /**
     * Resend OTP code. Invalidates the old code, generates a new one.
     */
    @Transactional
    public ResendOtpResult resend(UUID codeId, String ipAddress, String userAgent, String otpSessionCookie) {
        // 1. Find the original code
        EmailVerificationCode originalCode = codeRepository.findById(codeId)
                .orElseThrow(() -> new ResourceNotFoundException("Verification code not found", "OTP_NOT_FOUND"));

        // 2. Verify fingerprint
        String fingerprint = sha256((otpSessionCookie != null ? otpSessionCookie : "") + ":" + (userAgent != null ? userAgent : ""));
        if (!MessageDigest.isEqual(
                fingerprint.getBytes(StandardCharsets.UTF_8),
                originalCode.getFingerprint().getBytes(StandardCharsets.UTF_8))) {
            throw new AccessDeniedException(
                    "Code must be resent from the same browser session", "OTP_FINGERPRINT_MISMATCH");
        }

        String email = originalCode.getEmail();

        // 3. Check cooldown
        Optional<EmailVerificationCode> latestCode = codeRepository.findLatestByEmail(email);
        if (latestCode.isPresent()) {
            long secondsSinceLastSend = Instant.now().getEpochSecond() - latestCode.get().getCreatedTs().getEpochSecond();
            if (secondsSinceLastSend < otpConfig.getSendCooldown()) {
                throw new RateLimitExceededException(
                        "Please wait before requesting a new code", "OTP_SEND_RATE_LIMITED");
            }
        }

        // 4. Check window rate limit
        Instant windowStart = Instant.now().minusSeconds(otpConfig.getSendWindowDuration());
        long sendCount = codeRepository.countByEmailSince(email, windowStart);
        if (sendCount >= otpConfig.getSendWindowMax()) {
            throw new RateLimitExceededException(
                    "Too many code requests. Please try again later.", "OTP_SEND_LIMIT");
        }

        // 5. Per-IP rate limit
        checkIpRateLimit(ipAddress);

        // 6. Invalidate old code
        codeRepository.invalidateAllByEmail(email);

        // 7. Generate new code
        int codeInt = secureRandom.nextInt(100000, 1000000);
        String code = String.valueOf(codeInt);
        String codeHash = hmacSha256(code, otpConfig.getHmacSecret());

        EmailVerificationCode newCode = EmailVerificationCode.builder()
                .email(email)
                .codeHash(codeHash)
                .purpose("register")
                .fingerprint(fingerprint)
                .attempts(0)
                .maxAttempts(otpConfig.getMaxVerifyAttempts())
                .isUsed(false)
                .isBlocked(false)
                .expiresAt(Instant.now().plusSeconds(otpConfig.getCodeTtl()))
                .build();
        newCode = codeRepository.save(newCode);

        // 8. Send email
        emailService.sendVerificationCode(email, code);

        // 9. Record IP attempt
        recordIpAttempt(ipAddress);

        log.info("OTP resent for email: {}", maskEmail(email));

        InitiateOtpResponse response = InitiateOtpResponse.builder()
                .message("Verification code sent")
                .codeId(newCode.getId())
                .expiresIn(otpConfig.getCodeTtl())
                .resendAvailableIn(otpConfig.getSendCooldown())
                .build();

        return new ResendOtpResult(response);
    }

    /**
     * Update user profile (first name, last name).
     */
    @Transactional
    public UpdateProfileResponse updateProfile(UUID userId, UUID tenantId, String firstName, String lastName,
                                                String ipAddress, String userAgent) {
        User user = userRepository.findByIdAndTenantId(userId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found", "USER_NOT_FOUND"));

        if (firstName != null) {
            user.setFirstName(firstName.trim().isEmpty() ? null : firstName.trim());
        }
        if (lastName != null) {
            user.setLastName(lastName.trim().isEmpty() ? null : lastName.trim());
        }

        user = userRepository.save(user);

        auditService.logAction(tenantId, userId, "PROFILE_UPDATED", "USERS", userId,
                Map.of("first_name", user.getFirstName() != null ? user.getFirstName() : "",
                        "last_name", user.getLastName() != null ? user.getLastName() : ""),
                ipAddress, userAgent, getCorrelationId());

        return UpdateProfileResponse.builder()
                .id(user.getId())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .updatedTs(user.getUpdatedTs())
                .build();
    }

    /**
     * Set or change password for the current user.
     */
    @Transactional
    public void setPassword(UUID userId, UUID tenantId, String currentPassword, String newPassword,
                            String ipAddress, String userAgent) {
        User user = userRepository.findByIdAndTenantId(userId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found", "USER_NOT_FOUND"));

        if (Boolean.TRUE.equals(user.getIsPasswordSet())) {
            // Changing existing password - current password required
            if (currentPassword == null || currentPassword.isBlank()) {
                throw new BadRequestException(
                        "Current password is required to change password", "CURRENT_PASSWORD_REQUIRED");
            }
            if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
                throw new InvalidCredentialsException(
                        "Current password is incorrect", "INVALID_CURRENT_PASSWORD");
            }
        }

        // Determine action before modifying the entity
        String action = Boolean.TRUE.equals(user.getIsPasswordSet()) ? "PASSWORD_CHANGED" : "PASSWORD_SET";

        // Set new password
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setIsPasswordSet(true);
        userRepository.save(user);
        auditService.logAction(tenantId, userId, action, "USERS", userId,
                null, ipAddress, userAgent, getCorrelationId());

        log.info("Password set for user_id={}", userId);
    }

    /**
     * Scheduled cleanup of expired verification codes (runs every hour).
     */
    @Scheduled(fixedRate = 3600000)
    @Transactional
    public void cleanupExpiredCodes() {
        Instant cutoff = Instant.now().minusSeconds(86400); // 24 hours ago
        int deleted = codeRepository.deleteExpiredBefore(cutoff);
        if (deleted > 0) {
            log.info("Cleaned up {} expired verification codes", deleted);
        }
    }

    // --- Helper methods ---

    String normalizeEmail(String email) {
        email = email.trim().toLowerCase();
        String[] parts = email.split("@", 2);
        if (parts.length != 2) return email;
        String local = parts[0];
        String domain = parts[1];

        // Remove sub-addressing (user+tag@domain -> user@domain)
        int plusIdx = local.indexOf('+');
        if (plusIdx > 0) local = local.substring(0, plusIdx);

        // Gmail dot-trick: first.last@gmail.com == firstlast@gmail.com
        if ("gmail.com".equals(domain) || "googlemail.com".equals(domain)) {
            local = local.replace(".", "");
            domain = "gmail.com";
        }

        return local + "@" + domain;
    }

    private String hmacSha256(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 computation failed", e);
        }
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    private void checkIpRateLimit(String ipAddress) {
        List<Long> attempts = ipAttempts.get(ipAddress);
        if (attempts == null) return;
        long windowStart = System.currentTimeMillis() - (otpConfig.getIpBurstWindow() * 1000L);
        long recentAttempts = attempts.stream().filter(ts -> ts > windowStart).count();
        if (recentAttempts >= otpConfig.getIpBurstMax()) {
            throw new RateLimitExceededException("Too many requests. Please try again later.", "TOO_MANY_REQUESTS");
        }
    }

    private void recordIpAttempt(String ipAddress) {
        ipAttempts.computeIfAbsent(ipAddress, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(System.currentTimeMillis());
        long windowStart = System.currentTimeMillis() - (otpConfig.getIpBurstWindow() * 1000L);
        ipAttempts.computeIfPresent(ipAddress, (k, v) -> {
            v.removeIf(ts -> ts < windowStart);
            return v.isEmpty() ? null : v;
        });
    }

    private String generateSlug() {
        String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder("company-");
        for (int i = 0; i < 6; i++) {
            sb.append(chars.charAt(secureRandom.nextInt(chars.length())));
        }
        String slug = sb.toString();
        // Ensure uniqueness
        while (tenantRepository.existsBySlug(slug)) {
            sb = new StringBuilder("company-");
            for (int i = 0; i < 6; i++) {
                sb.append(chars.charAt(secureRandom.nextInt(chars.length())));
            }
            slug = sb.toString();
        }
        return slug;
    }

    private List<String> determineScopesForRoles(List<String> roles) {
        if (roles.contains("SUPER_ADMIN")) return List.of("global");
        if (roles.contains("OPERATOR")) return List.of("own");
        return List.of("tenant");
    }

    private String maskEmail(String email) {
        int atIdx = email.indexOf('@');
        if (atIdx <= 2) return "***" + email.substring(atIdx);
        return email.substring(0, 2) + "***" + email.substring(atIdx);
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

    // --- Result holder types ---

    public record VerifyOtpResult(VerifyOtpResponse response, String rawRefreshToken) {}
    public record ResendOtpResult(InitiateOtpResponse response) {}
}
