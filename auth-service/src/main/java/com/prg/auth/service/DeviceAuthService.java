package com.prg.auth.service;

import com.prg.auth.config.JwtConfig;
import com.prg.auth.dto.request.DeviceLoginRequest;
import com.prg.auth.dto.request.DeviceRefreshRequest;
import com.prg.auth.dto.response.*;
import com.prg.auth.entity.*;
import com.prg.auth.exception.*;
import com.prg.auth.repository.*;
import com.prg.auth.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeviceAuthService {

    private final DeviceRegistrationTokenRepository registrationTokenRepository;
    private final DeviceRepository deviceRepository;
    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final JwtConfig jwtConfig;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    @Value("${prg.security.max-login-attempts}")
    private int maxLoginAttempts;

    @Value("${prg.security.login-attempt-window}")
    private long loginAttemptWindow;

    @Value("${prg.device.heartbeat-interval-sec:30}")
    private int heartbeatIntervalSec;

    @Value("${prg.device.segment-duration-sec:10}")
    private int segmentDurationSec;

    @Value("${prg.device.capture-fps:5}")
    private int captureFps;

    @Value("${prg.device.quality:medium}")
    private String quality;

    @Value("${prg.device.ingest-base-url:}")
    private String ingestBaseUrl;

    @Value("${prg.device.control-plane-base-url:}")
    private String controlPlaneBaseUrl;

    @Value("${prg.device.default-resolution:720p}")
    private String defaultResolution;

    @Value("${prg.device.default-session-max-hours:24}")
    private int defaultSessionMaxHours;

    @Value("${prg.device.default-auto-start:true}")
    private boolean defaultAutoStart;

    private final ConcurrentHashMap<String, List<Long>> loginAttempts = new ConcurrentHashMap<>();

    @Transactional
    public DeviceLoginResponse deviceLogin(DeviceLoginRequest request, String ipAddress, String userAgent) {
        boolean hasCredentials = request.getUsername() != null && !request.getUsername().isBlank()
                && request.getPassword() != null && !request.getPassword().isBlank();

        String rateLimitKey = ipAddress + ":device:" + (hasCredentials ? request.getUsername() : "token-only");
        checkRateLimit(rateLimitKey);

        // 1. Validate registration token
        String tokenHash = AuthService.sha256(request.getRegistrationToken());
        DeviceRegistrationToken regToken = registrationTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> {
                    recordLoginAttempt(rateLimitKey);
                    return new InvalidCredentialsException("Invalid registration token", "INVALID_REGISTRATION_TOKEN");
                });

        if (!regToken.getIsActive()) {
            recordLoginAttempt(rateLimitKey);
            throw new InvalidCredentialsException("Registration token is deactivated", "REGISTRATION_TOKEN_INACTIVE");
        }

        if (regToken.getExpiresAt() != null && regToken.getExpiresAt().isBefore(Instant.now())) {
            recordLoginAttempt(rateLimitKey);
            throw new InvalidCredentialsException("Registration token has expired", "REGISTRATION_TOKEN_EXPIRED");
        }

        if (regToken.getMaxUses() != null && regToken.getCurrentUses() >= regToken.getMaxUses()) {
            recordLoginAttempt(rateLimitKey);
            throw new InvalidCredentialsException("Registration token usage limit exceeded", "REGISTRATION_TOKEN_EXHAUSTED");
        }

        // 2. Extract tenant_id from token
        UUID tenantId = regToken.getTenant().getId();

        User user;
        if (hasCredentials) {
            // 3a. Validate username + password in tenant context (backward compatibility)
            if (request.getUsername().length() < 3) {
                recordLoginAttempt(rateLimitKey);
                throw new InvalidCredentialsException("Invalid username or password");
            }
            user = userRepository.findByTenantIdAndUsername(tenantId, request.getUsername())
                    .orElse(null);

            if (user == null) {
                recordLoginAttempt(rateLimitKey);
                auditService.logAction(tenantId, null, "DEVICE_LOGIN_FAILED", "AUTH", null,
                        Map.of("username", request.getUsername(), "reason", "user_not_found",
                                "hardware_id", request.getDeviceInfo().getHardwareId()),
                        ipAddress, userAgent, getCorrelationId());
                throw new InvalidCredentialsException("Invalid username or password");
            }

            if (!user.getIsActive()) {
                recordLoginAttempt(rateLimitKey);
                auditService.logAction(tenantId, user.getId(), "DEVICE_LOGIN_FAILED", "AUTH", null,
                        Map.of("reason", "account_disabled",
                                "hardware_id", request.getDeviceInfo().getHardwareId()),
                        ipAddress, userAgent, getCorrelationId());
                throw new InvalidCredentialsException("Invalid username or password");
            }

            if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
                recordLoginAttempt(rateLimitKey);
                auditService.logAction(tenantId, user.getId(), "DEVICE_LOGIN_FAILED", "AUTH", null,
                        Map.of("reason", "invalid_password",
                                "hardware_id", request.getDeviceInfo().getHardwareId()),
                        ipAddress, userAgent, getCorrelationId());
                throw new InvalidCredentialsException("Invalid username or password");
            }
        } else {
            // 3b. Token-only mode: use the user who created the registration token
            user = regToken.getCreatedBy();

            if (user == null) {
                recordLoginAttempt(rateLimitKey);
                throw new InvalidCredentialsException("Registration token creator not found", "TOKEN_CREATOR_NOT_FOUND");
            }

            if (!user.getIsActive()) {
                recordLoginAttempt(rateLimitKey);
                auditService.logAction(tenantId, user.getId(), "DEVICE_LOGIN_FAILED", "AUTH", null,
                        Map.of("reason", "token_creator_disabled",
                                "hardware_id", request.getDeviceInfo().getHardwareId()),
                        ipAddress, userAgent, getCorrelationId());
                throw new InvalidCredentialsException("Token creator account is disabled");
            }

            log.info("Device login token-only mode: using token creator user_id={}, tenant_id={}",
                    user.getId(), tenantId);
        }

        // 4. Check role: OWNER, TENANT_ADMIN, MANAGER, or SUPER_ADMIN
        List<String> userRoles = user.getRoles().stream().map(Role::getCode).toList();
        boolean hasRequiredRole = userRoles.contains("OWNER") || userRoles.contains("TENANT_ADMIN")
                || userRoles.contains("MANAGER") || userRoles.contains("SUPER_ADMIN");
        if (!hasRequiredRole) {
            recordLoginAttempt(rateLimitKey);
            auditService.logAction(tenantId, user.getId(), "DEVICE_LOGIN_FAILED", "AUTH", null,
                    Map.of("reason", "insufficient_role", "roles", userRoles.toString(),
                            "hardware_id", request.getDeviceInfo().getHardwareId()),
                    ipAddress, userAgent, getCorrelationId());
            throw new AccessDeniedException("Insufficient role for device registration. Required: OWNER, TENANT_ADMIN or MANAGER");
        }

        loginAttempts.remove(rateLimitKey);

        // 5. Find or create device
        DeviceLoginRequest.DeviceInfo deviceInfo = request.getDeviceInfo();
        String auditAction;
        Device device = deviceRepository.findByTenantIdAndHardwareId(tenantId, deviceInfo.getHardwareId())
                .orElse(null);

        if (device != null) {
            // 7. Auto-restore soft-deleted device on re-login
            if (Boolean.TRUE.equals(device.getIsDeleted())) {
                device.setIsDeleted(false);
                device.setDeletedTs(null);
                device.setIsActive(true);
                log.info("DEVICE_AUTO_RESTORED: device_id={}, tenant_id={}, trigger=device-login",
                        device.getId(), tenantId);
                auditAction = "DEVICE_RESTORED";
            } else if (!device.getIsActive()) {
                throw new DeviceDeactivatedException("Device has been deactivated. Contact your administrator.");
            } else {
                auditAction = "DEVICE_RELOGIN";
            }

            // 8. Token accounting: handle registration token link on existing device
            DeviceRegistrationToken previousToken = device.getRegistrationToken();

            if (previousToken == null) {
                // Device has no linked token (created before token tracking or lost link)
                // Link the current token and increment its counter
                device.setRegistrationToken(regToken);
                registrationTokenRepository.incrementCurrentUses(regToken.getId());

                log.info("DEVICE_TOKEN_LINKED: device_id={}, token={}, tenant_id={} (previously unlinked)",
                        device.getId(), regToken.getId(), tenantId);

                auditService.logAction(tenantId, user.getId(), "DEVICE_TOKEN_LINKED", "DEVICES", device.getId(),
                        Map.of("token_id", regToken.getId().toString(),
                                "token_name", regToken.getName() != null ? regToken.getName() : "",
                                "reason", "previously_unlinked"),
                        ipAddress, userAgent, getCorrelationId());
            } else if (!previousToken.getId().equals(regToken.getId())) {
                // Token changed: decrement old, increment new
                registrationTokenRepository.decrementCurrentUses(previousToken.getId());
                registrationTokenRepository.incrementCurrentUses(regToken.getId());
                device.setRegistrationToken(regToken);

                log.info("DEVICE_TOKEN_CHANGED: device_id={}, old_token={}, new_token={}, tenant_id={}",
                        device.getId(), previousToken.getId(), regToken.getId(), tenantId);

                auditService.logAction(tenantId, user.getId(), "DEVICE_TOKEN_CHANGED", "DEVICES", device.getId(),
                        Map.of("old_token_id", previousToken.getId().toString(),
                                "old_token_name", previousToken.getName() != null ? previousToken.getName() : "",
                                "new_token_id", regToken.getId().toString(),
                                "new_token_name", regToken.getName() != null ? regToken.getName() : ""),
                        ipAddress, userAgent, getCorrelationId());
            }
            // else: same token, no changes needed

            // 6. Update existing device
            device.setHostname(deviceInfo.getHostname());
            device.setOsVersion(deviceInfo.getOsVersion());
            device.setAgentVersion(deviceInfo.getAgentVersion());
            device.setIpAddress(ipAddress);
            device.setUser(user);
            device.setStatus("online");
            device = deviceRepository.save(device);
        } else {
            // Create new device
            device = Device.builder()
                    .tenant(regToken.getTenant())
                    .user(user)
                    .registrationToken(regToken)
                    .hostname(deviceInfo.getHostname())
                    .osVersion(deviceInfo.getOsVersion())
                    .agentVersion(deviceInfo.getAgentVersion())
                    .hardwareId(deviceInfo.getHardwareId())
                    .status("online")
                    .ipAddress(ipAddress)
                    .isActive(true)
                    .settings(Map.of())
                    .build();
            device = deviceRepository.save(device);
            auditAction = "DEVICE_REGISTERED";

            // 8. Increment current_uses only for genuinely new device registrations
            registrationTokenRepository.incrementCurrentUses(regToken.getId());
        }

        // 9. Generate JWT with device_id claim
        List<String> roles = user.getRoles().stream().map(Role::getCode).toList();
        List<String> permissions = user.getRoles().stream()
                .flatMap(r -> r.getPermissions().stream())
                .map(Permission::getCode)
                .distinct().sorted().toList();
        List<String> scopes = determineScopesForRoles(roles);

        String accessToken = jwtTokenProvider.generateDeviceAccessToken(
                user.getId(), tenantId, user.getUsername(), user.getEmail(),
                roles, permissions, scopes, device.getId());

        // 10. Generate refresh token, store hash
        String rawRefreshToken = UUID.randomUUID().toString();
        String refreshTokenHash = AuthService.sha256(rawRefreshToken);

        RefreshToken refreshToken = RefreshToken.builder()
                .user(user)
                .tenantId(tenantId)
                .tokenHash(refreshTokenHash)
                .deviceInfo("device:" + device.getId() + "|" + deviceInfo.getHostname())
                .ipAddress(ipAddress)
                .expiresAt(Instant.now().plusSeconds(jwtConfig.getRefreshTokenTtl()))
                .isRevoked(false)
                .build();
        refreshTokenRepository.save(refreshToken);

        userRepository.updateLastLoginTs(user.getId(), Instant.now());

        // 12. Audit
        auditService.logAction(tenantId, user.getId(), auditAction, "DEVICES", device.getId(),
                Map.of("hostname", deviceInfo.getHostname(),
                        "hardware_id", deviceInfo.getHardwareId(),
                        "agent_version", deviceInfo.getAgentVersion() != null ? deviceInfo.getAgentVersion() : "unknown"),
                ipAddress, userAgent, getCorrelationId());

        log.info("Device login successful: device_id={}, user={}, tenant_id={}, action={}",
                device.getId(), user.getUsername(), tenantId, auditAction);

        // Build user response
        List<RoleResponse> roleResponses = user.getRoles().stream()
                .map(r -> RoleResponse.builder().code(r.getCode()).name(r.getName()).build())
                .toList();

        UserResponse userResponse = UserResponse.builder()
                .id(user.getId())
                .tenantId(tenantId)
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

        // Build server config with device-specific overrides from settings JSONB
        Map<String, Object> deviceSettings = device.getSettings() != null ? device.getSettings() : Map.of();

        String resolution = deviceSettings.containsKey("resolution")
                ? String.valueOf(deviceSettings.get("resolution")) : defaultResolution;
        Integer sessionMaxDurationHours = deviceSettings.containsKey("session_max_duration_hours")
                ? toInteger(deviceSettings.get("session_max_duration_hours")) : defaultSessionMaxHours;
        Boolean autoStart = deviceSettings.containsKey("auto_start")
                ? toBoolean(deviceSettings.get("auto_start")) : defaultAutoStart;

        DeviceLoginResponse.ServerConfig serverConfig = DeviceLoginResponse.ServerConfig.builder()
                .heartbeatIntervalSec(heartbeatIntervalSec)
                .segmentDurationSec(segmentDurationSec)
                .captureFps(captureFps)
                .quality(quality)
                .ingestBaseUrl(ingestBaseUrl)
                .controlPlaneBaseUrl(controlPlaneBaseUrl)
                .resolution(resolution)
                .sessionMaxDurationHours(sessionMaxDurationHours)
                .autoStart(autoStart)
                .build();

        // 11. Return refresh_token in body
        return DeviceLoginResponse.builder()
                .accessToken(accessToken)
                .refreshToken(rawRefreshToken)
                .tokenType("Bearer")
                .expiresIn(jwtTokenProvider.getAccessTokenTtl())
                .deviceId(device.getId())
                .deviceStatus(device.getStatus())
                .user(userResponse)
                .serverConfig(serverConfig)
                .build();
    }

    @Transactional
    public DeviceRefreshResponse deviceRefresh(DeviceRefreshRequest request, String ipAddress, String userAgent) {
        // Validate refresh token first to get tenant context
        String tokenHash = AuthService.sha256(request.getRefreshToken());
        RefreshToken storedToken = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new TokenExpiredException("Refresh token not found or expired", "REFRESH_TOKEN_EXPIRED"));

        // Validate device exists, is active, and belongs to the same tenant
        Device device = deviceRepository.findByIdAndTenantId(request.getDeviceId(), storedToken.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Device not found", "DEVICE_NOT_FOUND"));

        // Auto-restore soft-deleted device on token refresh
        if (Boolean.TRUE.equals(device.getIsDeleted())) {
            device.setIsDeleted(false);
            device.setDeletedTs(null);
            device.setIsActive(true);
            deviceRepository.save(device);
            log.info("DEVICE_AUTO_RESTORED: device_id={}, tenant_id={}, trigger=device-refresh",
                    device.getId(), storedToken.getTenantId());
        }

        if (!device.getIsActive()) {
            throw new DeviceDeactivatedException("Device has been deactivated. Contact your administrator.");
        }

        if (storedToken.getIsRevoked()) {
            log.warn("Refresh token reuse detected for device: {}. Revoking all tokens for user: {}.",
                    request.getDeviceId(), storedToken.getUser().getId());
            refreshTokenRepository.revokeAllByUserId(storedToken.getUser().getId());
            throw new TokenExpiredException("Refresh token has been revoked. All sessions terminated.", "REFRESH_TOKEN_REVOKED");
        }

        if (storedToken.getExpiresAt().isBefore(Instant.now())) {
            refreshTokenRepository.revokeById(storedToken.getId());
            throw new TokenExpiredException("Refresh token has expired", "REFRESH_TOKEN_EXPIRED");
        }

        // Revoke old refresh token (rotation)
        refreshTokenRepository.revokeById(storedToken.getId());

        User user = storedToken.getUser();
        UUID tenantId = storedToken.getTenantId();
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found", "TENANT_NOT_FOUND"));

        if (!user.getIsActive() || !tenant.getIsActive()) {
            throw new InvalidCredentialsException("Account or tenant is disabled", "ACCOUNT_DISABLED");
        }

        // Generate new tokens
        List<String> roles = user.getRoles().stream().map(Role::getCode).toList();
        List<String> permissions = user.getRoles().stream()
                .flatMap(r -> r.getPermissions().stream())
                .map(Permission::getCode)
                .distinct().sorted().toList();
        List<String> scopes = determineScopesForRoles(roles);

        String accessToken = jwtTokenProvider.generateDeviceAccessToken(
                user.getId(), tenantId, user.getUsername(), user.getEmail(),
                roles, permissions, scopes, device.getId());

        String newRawRefreshToken = UUID.randomUUID().toString();
        String newTokenHash = AuthService.sha256(newRawRefreshToken);

        RefreshToken newRefreshToken = RefreshToken.builder()
                .user(user)
                .tenantId(tenantId)
                .tokenHash(newTokenHash)
                .deviceInfo("device:" + device.getId() + "|" + device.getHostname())
                .ipAddress(ipAddress)
                .expiresAt(Instant.now().plusSeconds(jwtConfig.getRefreshTokenTtl()))
                .isRevoked(false)
                .build();
        refreshTokenRepository.save(newRefreshToken);

        auditService.logAction(tenantId, user.getId(), "DEVICE_TOKEN_REFRESHED", "AUTH", device.getId(),
                null, ipAddress, userAgent, getCorrelationId());

        return DeviceRefreshResponse.builder()
                .accessToken(accessToken)
                .refreshToken(newRawRefreshToken)
                .tokenType("Bearer")
                .expiresIn(jwtTokenProvider.getAccessTokenTtl())
                .build();
    }

    /**
     * Validates a registration token without performing device login.
     * Returns token validity status along with tenant and token metadata.
     */
    @Transactional(readOnly = true)
    public ValidateRegistrationTokenResponse validateRegistrationToken(String rawToken, String ipAddress) {
        // Rate limit by IP to prevent token enumeration
        String rateLimitKey = ipAddress + ":validate-token";
        checkRateLimit(rateLimitKey);

        String tokenHash = AuthService.sha256(rawToken);
        Optional<DeviceRegistrationToken> optToken = registrationTokenRepository.findByTokenHash(tokenHash);

        if (optToken.isEmpty()) {
            recordLoginAttempt(rateLimitKey);
            return ValidateRegistrationTokenResponse.builder()
                    .valid(false)
                    .reason("INVALID_TOKEN")
                    .build();
        }

        DeviceRegistrationToken regToken = optToken.get();

        // For invalid states, do NOT expose tenant_name/token_name (security: information disclosure)
        if (!regToken.getIsActive()) {
            recordLoginAttempt(rateLimitKey);
            return ValidateRegistrationTokenResponse.builder()
                    .valid(false)
                    .reason("TOKEN_INACTIVE")
                    .build();
        }

        if (regToken.getExpiresAt() != null && regToken.getExpiresAt().isBefore(Instant.now())) {
            recordLoginAttempt(rateLimitKey);
            return ValidateRegistrationTokenResponse.builder()
                    .valid(false)
                    .reason("TOKEN_EXPIRED")
                    .build();
        }

        if (regToken.getMaxUses() != null && regToken.getCurrentUses() >= regToken.getMaxUses()) {
            recordLoginAttempt(rateLimitKey);
            return ValidateRegistrationTokenResponse.builder()
                    .valid(false)
                    .reason("TOKEN_EXHAUSTED")
                    .build();
        }

        // Only expose tenant/token metadata for valid tokens
        return ValidateRegistrationTokenResponse.builder()
                .valid(true)
                .tenantName(regToken.getTenant().getName())
                .tokenName(regToken.getName())
                .build();
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

    private UUID getCorrelationId() {
        String cid = MDC.get("correlation_id");
        if (cid != null) {
            try { return UUID.fromString(cid); } catch (IllegalArgumentException e) { return null; }
        }
        return null;
    }

    private Integer toInteger(Object value) {
        if (value instanceof Number) return ((Number) value).intValue();
        if (value instanceof String) {
            try { return Integer.parseInt((String) value); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    private Boolean toBoolean(Object value) {
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof String) return Boolean.parseBoolean((String) value);
        return null;
    }
}
