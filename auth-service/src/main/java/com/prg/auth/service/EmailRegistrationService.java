package com.prg.auth.service;

import com.prg.auth.dto.request.RegisterRequest;
import com.prg.auth.entity.*;
import com.prg.auth.exception.BadRequestException;
import com.prg.auth.exception.ConflictException;
import com.prg.auth.exception.RateLimitExceededException;
import com.prg.auth.exception.ResourceNotFoundException;
import com.prg.auth.repository.*;
import com.prg.auth.util.PasswordValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailRegistrationService {

    private static final UUID TEMPLATE_TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");
    private static final long VERIFICATION_TOKEN_TTL = 86400; // 24 hours
    private static final int RESEND_COOLDOWN = 60; // seconds
    private static final int MAX_RESENDS_PER_HOUR = 5;

    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final RoleRepository roleRepository;
    private final DisposableEmailDomainRepository disposableDomainRepository;
    private final EmailVerificationTokenRepository verificationTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final AuditService auditService;

    private final SecureRandom secureRandom = new SecureRandom();
    private final ConcurrentHashMap<String, List<Long>> ipAttempts = new ConcurrentHashMap<>();

    @Value("${prg.frontend.base-url:http://localhost:3000}")
    private String frontendBaseUrl;

    /**
     * Register a new user via email wizard.
     * Creates tenant + user (email_verified=false) + sends verification link.
     */
    @Transactional
    public Map<String, Object> register(RegisterRequest request, String ipAddress, String userAgent) {
        // 1. Rate limit
        checkIpRateLimit(ipAddress);

        // 2. Normalize email
        String email = normalizeEmail(request.getEmail());

        // 3. Validate password
        String passwordError = PasswordValidator.validate(request.getPassword());
        if (passwordError != null) {
            throw new BadRequestException(passwordError, "WEAK_PASSWORD");
        }

        // 4. Check disposable email
        String domain = email.substring(email.indexOf('@') + 1).toLowerCase();
        if (disposableDomainRepository.countByDomain(domain) > 0) {
            throw new BadRequestException("Registration with disposable email addresses is not allowed", "DISPOSABLE_EMAIL");
        }

        // 5. Check email uniqueness
        if (userRepository.existsByEmail(email)) {
            throw new ConflictException("Email is already registered");
        }

        // 6. Validate company name
        String companyName = request.getCompanyName().trim();
        if (companyName.length() < 2 || companyName.length() > 200) {
            throw new BadRequestException("Company name must be between 2 and 200 characters", "INVALID_COMPANY_NAME");
        }

        // 7. Create tenant
        String slug = generateSlug();
        Map<String, Object> tenantSettings = new HashMap<>();
        tenantSettings.put("session_ttl_max_days", 30);

        Tenant tenant = Tenant.builder()
                .name(companyName)
                .slug(slug)
                .isActive(true)
                .settings(tenantSettings)
                .build();
        tenant = tenantRepository.save(tenant);

        // 8. Copy system roles from template tenant
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

        // 9. Create user
        String firstName = request.getFirstName() != null ? request.getFirstName().trim() : null;
        String lastName = request.getLastName() != null ? request.getLastName().trim() : null;
        if (firstName != null && firstName.isBlank()) firstName = null;
        if (lastName != null && lastName.isBlank()) lastName = null;

        User user = User.builder()
                .tenant(tenant)
                .username(email)
                .email(email)
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .authProvider("password")
                .emailVerified(false)
                .isPasswordSet(true)
                .firstName(firstName)
                .lastName(lastName)
                .isActive(true)
                .settings(new HashMap<>())
                .build();

        // Assign OWNER role
        Role ownerRole = newRoles.get("OWNER");
        if (ownerRole == null) ownerRole = newRoles.get("TENANT_ADMIN");
        if (ownerRole != null) user.setRoles(Set.of(ownerRole));

        user = userRepository.save(user);

        // 10. Generate verification token
        String token = UUID.randomUUID().toString();
        EmailVerificationToken verificationToken = EmailVerificationToken.builder()
                .userId(user.getId())
                .token(token)
                .isUsed(false)
                .expiresAt(Instant.now().plusSeconds(VERIFICATION_TOKEN_TTL))
                .build();
        verificationTokenRepository.save(verificationToken);

        // 11. Send verification email
        String verifyLink = frontendBaseUrl + "/verify-email/" + token;
        try {
            emailService.sendVerificationLink(email, firstName, verifyLink);
        } catch (Exception e) {
            log.error("Failed to send verification email to {}: {}", maskEmail(email), e.getMessage());
            throw new BadRequestException("Failed to send verification email. Please try again later.", "EMAIL_DELIVERY_FAILED");
        }

        // 12. Record IP attempt
        recordIpAttempt(ipAddress);

        // 13. Audit
        try {
            auditService.logAction(tenant.getId(), user.getId(), "EMAIL_REGISTER_INITIATED", "AUTH", user.getId(),
                    Map.of("email", maskEmail(email), "tenant_slug", slug),
                    ipAddress, userAgent, null);
        } catch (Exception e) {
            log.warn("Failed to audit EMAIL_REGISTER_INITIATED: {}", e.getMessage());
        }

        log.info("Email registration initiated: user_id={}, tenant_id={}, email={}",
                user.getId(), tenant.getId(), maskEmail(email));

        return Map.of(
                "message", "Verification email sent",
                "email", email
        );
    }

    /**
     * Verify email by token (called when user clicks the link).
     * Returns user entity if successful.
     */
    @Transactional
    public User verifyEmail(String token) {
        EmailVerificationToken verificationToken = verificationTokenRepository.findByToken(token)
                .orElseThrow(() -> new ResourceNotFoundException("Verification token not found", "TOKEN_NOT_FOUND"));

        if (verificationToken.getIsUsed()) {
            throw new BadRequestException("This verification link has already been used", "TOKEN_ALREADY_USED");
        }

        if (verificationToken.getExpiresAt().isBefore(Instant.now())) {
            throw new BadRequestException("Verification link has expired", "TOKEN_EXPIRED");
        }

        // Mark token as used
        verificationToken.setIsUsed(true);
        verificationTokenRepository.save(verificationToken);

        // Verify email
        User user = userRepository.findById(verificationToken.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found", "USER_NOT_FOUND"));

        user.setEmailVerified(true);
        userRepository.save(user);

        // Re-fetch with roles eagerly loaded (for auto-login)
        user = userRepository.findActiveByEmail(user.getEmail())
                .orElseThrow(() -> new ResourceNotFoundException("User not found", "USER_NOT_FOUND"));

        // Audit
        try {
            auditService.logAction(user.getTenant().getId(), user.getId(), "EMAIL_VERIFIED", "AUTH", user.getId(),
                    Map.of("email", maskEmail(user.getEmail())),
                    null, null, null);
        } catch (Exception e) {
            log.warn("Failed to audit EMAIL_VERIFIED: {}", e.getMessage());
        }

        log.info("Email verified: user_id={}, email={}", user.getId(), maskEmail(user.getEmail()));
        return user;
    }

    /**
     * Resend verification email.
     */
    @Transactional
    public Map<String, Object> resendVerification(String rawEmail, String ipAddress) {
        checkIpRateLimit(ipAddress);

        String email = normalizeEmail(rawEmail);
        User user = userRepository.findActiveByEmail(email).orElse(null);

        if (user == null || Boolean.TRUE.equals(user.getEmailVerified())) {
            // Don't reveal whether user exists
            return Map.of("message", "If this email is registered, a verification link has been sent",
                    "resend_available_in", RESEND_COOLDOWN);
        }

        // Check cooldown
        Instant hourAgo = Instant.now().minusSeconds(3600);
        long recentCount = verificationTokenRepository.countByUserIdSince(user.getId(), hourAgo);
        if (recentCount >= MAX_RESENDS_PER_HOUR) {
            throw new RateLimitExceededException("Too many verification requests. Please try again later.", "RESEND_LIMIT");
        }

        // Invalidate old tokens
        verificationTokenRepository.invalidateAllByUserId(user.getId());

        // Create new token
        String token = UUID.randomUUID().toString();
        EmailVerificationToken verificationToken = EmailVerificationToken.builder()
                .userId(user.getId())
                .token(token)
                .isUsed(false)
                .expiresAt(Instant.now().plusSeconds(VERIFICATION_TOKEN_TTL))
                .build();
        verificationTokenRepository.save(verificationToken);

        // Send email
        String verifyLink = frontendBaseUrl + "/verify-email/" + token;
        try {
            emailService.sendVerificationLink(email, user.getFirstName(), verifyLink);
        } catch (Exception e) {
            log.error("Failed to resend verification email: {}", e.getMessage());
            throw new BadRequestException("Failed to send verification email", "EMAIL_DELIVERY_FAILED");
        }

        recordIpAttempt(ipAddress);
        log.info("Verification email resent for: {}", maskEmail(email));

        return Map.of("message", "Verification email sent",
                "resend_available_in", RESEND_COOLDOWN);
    }

    @Scheduled(fixedRate = 86400000) // daily
    @Transactional
    public void cleanupExpiredTokens() {
        int deleted = verificationTokenRepository.deleteExpiredBefore(Instant.now().minusSeconds(172800)); // 48h
        if (deleted > 0) {
            log.info("Cleaned up {} expired verification tokens", deleted);
        }
    }

    // --- Helper methods ---

    private String normalizeEmail(String email) {
        email = email.trim().toLowerCase();
        String[] parts = email.split("@", 2);
        if (parts.length != 2) return email;
        String local = parts[0];
        String domainPart = parts[1];
        int plusIdx = local.indexOf('+');
        if (plusIdx > 0) local = local.substring(0, plusIdx);
        if ("gmail.com".equals(domainPart) || "googlemail.com".equals(domainPart)) {
            local = local.replace(".", "");
            domainPart = "gmail.com";
        }
        return local + "@" + domainPart;
    }

    private String generateSlug() {
        String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder("company-");
        for (int i = 0; i < 6; i++) {
            sb.append(chars.charAt(secureRandom.nextInt(chars.length())));
        }
        String slug = sb.toString();
        while (tenantRepository.existsBySlug(slug)) {
            sb = new StringBuilder("company-");
            for (int i = 0; i < 6; i++) {
                sb.append(chars.charAt(secureRandom.nextInt(chars.length())));
            }
            slug = sb.toString();
        }
        return slug;
    }

    private void checkIpRateLimit(String ipAddress) {
        List<Long> attempts = ipAttempts.get(ipAddress);
        if (attempts == null) return;
        long windowStart = System.currentTimeMillis() - 300000; // 5 minutes
        long recentAttempts = attempts.stream().filter(ts -> ts > windowStart).count();
        if (recentAttempts >= 20) {
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
