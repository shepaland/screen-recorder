package com.prg.auth.service;

import com.prg.auth.dto.request.OnboardingRequest;
import com.prg.auth.dto.response.LoginResponse;
import com.prg.auth.dto.response.OAuthCallbackResponse;
import com.prg.auth.dto.response.OnboardingResponse;
import com.prg.auth.dto.response.RoleResponse;
import com.prg.auth.dto.response.UserResponse;
import com.prg.auth.entity.*;
import com.prg.auth.exception.DuplicateResourceException;
import com.prg.auth.exception.ResourceNotFoundException;
import com.prg.auth.repository.*;
import com.prg.auth.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class OnboardingService {

    private static final UUID TEMPLATE_TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final UserOAuthLinkRepository linkRepository;
    private final OAuthIdentityRepository identityRepository;
    private final RoleRepository roleRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final OAuthService oauthService;
    private final JwtTokenProvider jwtTokenProvider;
    private final AuditService auditService;

    /**
     * Onboard a new OAuth user: create tenant, copy roles, create user, link OAuth identity.
     */
    @Transactional
    public OnboardingResult onboard(String oauthToken, OnboardingRequest request,
                                     String ipAddress, String userAgent) {
        // 1. Validate OAuth intermediate token
        OAuthIdentity identity = oauthService.validateOAuthIntermediateToken(oauthToken);

        log.info("Starting onboarding: oauth_identity_id={}, email={}, slug={}",
                identity.getId(), identity.getEmail(), request.getSlug());

        // 2. Check slug uniqueness
        if (tenantRepository.existsBySlug(request.getSlug())) {
            throw new DuplicateResourceException("Tenant slug already exists", "SLUG_ALREADY_EXISTS");
        }

        // 3. Create Tenant
        Map<String, Object> tenantSettings = new HashMap<>();
        tenantSettings.put("session_ttl_max_days", 30);

        Tenant tenant = Tenant.builder()
                .name(request.getCompanyName())
                .slug(request.getSlug())
                .isActive(true)
                .settings(tenantSettings)
                .build();
        tenant = tenantRepository.save(tenant);

        MDC.put("tenant_id", tenant.getId().toString());

        // 4. Copy system roles from template tenant
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

        log.debug("Copied {} system roles from template to tenant {}", newRoles.size(), tenant.getSlug());

        // 5. Create User
        String firstName = request.getFirstName();
        String lastName = request.getLastName();
        if (firstName == null || firstName.isBlank()) {
            firstName = identity.getName();
        }

        User user = User.builder()
                .tenant(tenant)
                .username(identity.getEmail())
                .email(identity.getEmail())
                .passwordHash(null)
                .authProvider("oauth")
                .firstName(firstName)
                .lastName(lastName)
                .isActive(true)
                .settings(new HashMap<>())
                .build();

        // 6. Assign OWNER role
        Role ownerRole = newRoles.get("OWNER");
        if (ownerRole == null) {
            // Fallback to TENANT_ADMIN if OWNER role does not exist
            ownerRole = newRoles.get("TENANT_ADMIN");
        }
        if (ownerRole != null) {
            user.setRoles(Set.of(ownerRole));
        }

        user = userRepository.save(user);

        MDC.put("user_id", user.getId().toString());

        // 7. Create UserOAuthLink
        UserOAuthLink link = UserOAuthLink.builder()
                .user(user)
                .oauthIdentity(identity)
                .linkedBy(user)
                .build();
        linkRepository.save(link);

        log.info("Onboarding complete: tenant_id={}, user_id={}, oauth_identity_id={}",
                tenant.getId(), user.getId(), identity.getId());

        // 8. Generate tokens
        AuthResult<LoginResponse> loginResult = oauthService.performOAuthLogin(user, tenant, ipAddress, userAgent);

        // 9. Audit
        auditService.logAction(tenant.getId(), user.getId(), "ONBOARDING_COMPLETE", "AUTH", user.getId(),
                Map.of("tenant_slug", tenant.getSlug(), "provider", "yandex"),
                ipAddress, userAgent, getCorrelationId());

        // 10. Build response
        String primaryRoleCode = user.getRoles().stream()
                .map(Role::getCode)
                .findFirst()
                .orElse(null);

        OAuthCallbackResponse.TenantPreview tenantPreview = OAuthCallbackResponse.TenantPreview.builder()
                .id(tenant.getId())
                .name(tenant.getName())
                .slug(tenant.getSlug())
                .role(primaryRoleCode)
                .isCurrent(true)
                .createdTs(tenant.getCreatedTs())
                .build();

        OnboardingResponse onboardingResponse = OnboardingResponse.builder()
                .accessToken(loginResult.getResponse().getAccessToken())
                .tokenType("Bearer")
                .expiresIn((int) loginResult.getResponse().getExpiresIn())
                .tenant(tenantPreview)
                .user(loginResult.getResponse().getUser())
                .build();

        return OnboardingResult.builder()
                .response(onboardingResponse)
                .rawRefreshToken(loginResult.getRawRefreshToken())
                .build();
    }

    private UUID getCorrelationId() {
        String cid = MDC.get("correlation_id");
        if (cid != null) {
            try { return UUID.fromString(cid); } catch (IllegalArgumentException e) { return null; }
        }
        return null;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class OnboardingResult {
        private OnboardingResponse response;
        private String rawRefreshToken;
    }
}
