package com.prg.auth.service;

import com.prg.auth.dto.request.CreateOwnTenantRequest;
import com.prg.auth.dto.request.CreateTenantRequest;
import com.prg.auth.dto.request.UpdateTenantRequest;
import com.prg.auth.dto.response.TenantResponse;
import com.prg.auth.entity.OAuthIdentity;
import com.prg.auth.entity.Role;
import com.prg.auth.entity.Tenant;
import com.prg.auth.entity.User;
import com.prg.auth.entity.UserOAuthLink;
import com.prg.auth.exception.AccessDeniedException;
import com.prg.auth.exception.DuplicateResourceException;
import com.prg.auth.exception.ResourceNotFoundException;
import com.prg.auth.repository.RoleRepository;
import com.prg.auth.repository.TenantRepository;
import com.prg.auth.repository.UserOAuthLinkRepository;
import com.prg.auth.repository.UserRepository;
import com.prg.auth.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class TenantService {

    private static final UUID TEMPLATE_TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");

    private final TenantRepository tenantRepository;
    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final UserOAuthLinkRepository userOAuthLinkRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<TenantResponse> getTenants(UserPrincipal principal) {
        List<Tenant> tenants;

        if (principal.hasScope("global")) {
            tenants = tenantRepository.findByIsActiveTrue();
        } else {
            // Find all active tenants where the user has an active account
            List<User> userAccounts = userRepository.findActiveUsersByUsername(principal.getUsername());
            tenants = userAccounts.stream()
                    .map(User::getTenant)
                    .distinct()
                    .toList();
        }

        return tenants.stream()
                .filter(t -> !t.getId().equals(TEMPLATE_TENANT_ID))
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public TenantResponse createTenant(CreateTenantRequest request, UserPrincipal principal,
                                        String ipAddress, String userAgent) {
        if (tenantRepository.existsBySlug(request.getSlug())) {
            throw new DuplicateResourceException("Tenant slug already exists", "SLUG_ALREADY_EXISTS");
        }

        // 1. Create tenant
        Tenant tenant = Tenant.builder()
                .name(request.getName())
                .slug(request.getSlug())
                .isActive(true)
                .settings(request.getSettings() != null ? request.getSettings() : Map.of())
                .build();
        tenant = tenantRepository.save(tenant);

        // 2. Copy system roles from template tenant
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

        // 3. Create admin user
        CreateTenantRequest.AdminUser adminReq = request.getAdminUser();
        Role tenantAdminRole = newRoles.get("TENANT_ADMIN");

        User adminUser = User.builder()
                .tenant(tenant)
                .username(adminReq.getUsername())
                .email(adminReq.getEmail())
                .passwordHash(passwordEncoder.encode(adminReq.getPassword()))
                .firstName(adminReq.getFirstName())
                .lastName(adminReq.getLastName())
                .isActive(true)
                .roles(tenantAdminRole != null ? Set.of(tenantAdminRole) : Set.of())
                .build();
        adminUser = userRepository.save(adminUser);

        // Audit
        auditService.logAction(tenant.getId(), principal.getUserId(), "TENANT_CREATED", "TENANTS", tenant.getId(),
                Map.of("name", tenant.getName(), "slug", tenant.getSlug(), "admin_user_id", adminUser.getId().toString()),
                ipAddress, userAgent, null);

        log.info("Tenant created: id={}, slug={}, admin_user_id={}", tenant.getId(), tenant.getSlug(), adminUser.getId());

        TenantResponse response = toResponse(tenant);
        response.setAdminUserId(adminUser.getId());
        return response;
    }

    /**
     * Create a new tenant and bind it to the current authenticated user.
     * For OAuth users: creates new user record + UserOAuthLink.
     * For password users: creates new user record with same credentials.
     */
    @Transactional
    public TenantResponse createOwnTenant(CreateOwnTenantRequest request, UserPrincipal principal,
                                            String ipAddress, String userAgent) {
        if (tenantRepository.existsBySlug(request.getSlug())) {
            throw new DuplicateResourceException("Tenant slug already exists", "SLUG_ALREADY_EXISTS");
        }

        // Find current user
        User currentUser = userRepository.findById(principal.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found", "USER_NOT_FOUND"));

        // 1. Create tenant
        Map<String, Object> settings = request.getSettings() != null ? request.getSettings() : Map.of("session_ttl_max_days", 30);
        Tenant tenant = Tenant.builder()
                .name(request.getName())
                .slug(request.getSlug())
                .isActive(true)
                .settings(settings)
                .build();
        tenant = tenantRepository.save(tenant);

        // 2. Copy system roles from template tenant
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

        // 3. Determine owner role (OWNER, fallback to TENANT_ADMIN)
        Role ownerRole = newRoles.get("OWNER");
        if (ownerRole == null) {
            ownerRole = newRoles.get("TENANT_ADMIN");
        }

        // 4. Create new user in the new tenant
        User newUser = User.builder()
                .tenant(tenant)
                .username(currentUser.getUsername())
                .email(currentUser.getEmail())
                .passwordHash(currentUser.getPasswordHash()) // copy password hash for password users, null for OAuth
                .firstName(currentUser.getFirstName())
                .lastName(currentUser.getLastName())
                .authProvider(currentUser.getAuthProvider())
                .isActive(true)
                .roles(ownerRole != null ? Set.of(ownerRole) : Set.of())
                .settings(Map.of())
                .build();
        newUser = userRepository.save(newUser);

        // 5. For OAuth users: link the new user to the same OAuth identity
        if ("oauth".equals(currentUser.getAuthProvider())) {
            UserOAuthLink existingLink = userOAuthLinkRepository.findByUserId(currentUser.getId())
                    .orElse(null);
            if (existingLink != null) {
                OAuthIdentity identity = existingLink.getOauthIdentity();
                UserOAuthLink newLink = UserOAuthLink.builder()
                        .user(newUser)
                        .oauthIdentity(identity)
                        .build();
                userOAuthLinkRepository.save(newLink);
            }
        }

        // 6. Audit
        auditService.logAction(tenant.getId(), principal.getUserId(), "TENANT_CREATED", "TENANTS", tenant.getId(),
                Map.of("name", tenant.getName(), "slug", tenant.getSlug(), "owner_user_id", newUser.getId().toString()),
                ipAddress, userAgent, null);

        log.info("Tenant created by user: tenant_id={}, slug={}, creator_id={}, new_user_id={}",
                tenant.getId(), tenant.getSlug(), principal.getUserId(), newUser.getId());

        TenantResponse response = toResponse(tenant);
        response.setAdminUserId(newUser.getId());
        return response;
    }

    @Transactional
    public TenantResponse updateTenant(UUID tenantId, UpdateTenantRequest request, UserPrincipal principal,
                                        String ipAddress, String userAgent) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found", "TENANT_NOT_FOUND"));

        // TENANT_ADMIN can only update their own tenant
        if (!principal.hasScope("global") && !principal.getTenantId().equals(tenantId)) {
            throw new com.prg.auth.exception.AccessDeniedException("You can only update your own tenant");
        }

        Map<String, Object> changes = new HashMap<>();

        if (request.getName() != null) {
            changes.put("name", request.getName());
            tenant.setName(request.getName());
        }
        if (request.getIsActive() != null && principal.hasScope("global")) {
            changes.put("is_active", request.getIsActive());
            tenant.setIsActive(request.getIsActive());
        }
        if (request.getSettings() != null) {
            changes.put("settings", request.getSettings());
            tenant.setSettings(request.getSettings());
        }

        tenant = tenantRepository.save(tenant);

        // Audit
        auditService.logAction(tenantId, principal.getUserId(), "TENANT_UPDATED", "TENANTS", tenantId,
                changes, ipAddress, userAgent, null);

        log.info("Tenant updated: id={}, slug={}", tenantId, tenant.getSlug());
        return toResponse(tenant);
    }

    /**
     * Transfer tenant ownership from current owner to a new user.
     * Removes OWNER role from current owner, assigns it to the new owner.
     */
    @Transactional
    public void transferOwnership(UUID tenantId, UUID newOwnerUserId, UserPrincipal principal,
                                   String ipAddress, String userAgent) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found", "TENANT_NOT_FOUND"));

        User newOwner = userRepository.findByIdAndTenantId(newOwnerUserId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Target user not found in this tenant", "USER_NOT_FOUND"));

        if (!newOwner.getIsActive()) {
            throw new AccessDeniedException("Cannot transfer ownership to an inactive user");
        }

        // Find OWNER role in this tenant
        Role ownerRole = roleRepository.findByTenantIdAndCode(tenantId, "OWNER")
                .orElseThrow(() -> new ResourceNotFoundException("OWNER role not found", "ROLE_NOT_FOUND"));

        // Find current owner(s) and remove OWNER role
        // The principal might be SUPER_ADMIN, so find current OWNER by role
        List<User> currentOwners = userRepository.findByTenantId(tenantId, org.springframework.data.domain.Pageable.unpaged())
                .getContent().stream()
                .filter(u -> u.getRoles().contains(ownerRole))
                .toList();

        for (User currentOwner : currentOwners) {
            Set<Role> updatedRoles = new HashSet<>(currentOwner.getRoles());
            updatedRoles.remove(ownerRole);

            // If removing OWNER leaves no roles, assign TENANT_ADMIN
            if (updatedRoles.isEmpty()) {
                roleRepository.findByTenantIdAndCode(tenantId, "TENANT_ADMIN")
                        .ifPresent(updatedRoles::add);
            }
            currentOwner.setRoles(updatedRoles);
            userRepository.save(currentOwner);
        }

        // Assign OWNER role to the new owner
        Set<Role> newOwnerRoles = new HashSet<>(newOwner.getRoles());
        newOwnerRoles.add(ownerRole);
        newOwner.setRoles(newOwnerRoles);
        userRepository.save(newOwner);

        // Audit
        auditService.logAction(tenantId, principal.getUserId(), "OWNERSHIP_TRANSFERRED", "TENANTS", tenantId,
                Map.of("new_owner_user_id", newOwnerUserId.toString(),
                        "previous_owners", currentOwners.stream().map(u -> u.getId().toString()).toList()),
                ipAddress, userAgent, null);

        log.info("Tenant ownership transferred: tenant_id={}, new_owner_id={}", tenantId, newOwnerUserId);
    }

    private TenantResponse toResponse(Tenant tenant) {
        return TenantResponse.builder()
                .id(tenant.getId())
                .name(tenant.getName())
                .slug(tenant.getSlug())
                .isActive(tenant.getIsActive())
                .settings(tenant.getSettings())
                .createdTs(tenant.getCreatedTs())
                .updatedTs(tenant.getUpdatedTs())
                .build();
    }
}
