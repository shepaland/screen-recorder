package com.prg.auth.service;

import com.prg.auth.dto.request.CreateTenantRequest;
import com.prg.auth.dto.request.UpdateTenantRequest;
import com.prg.auth.dto.response.TenantResponse;
import com.prg.auth.entity.Role;
import com.prg.auth.entity.Tenant;
import com.prg.auth.entity.User;
import com.prg.auth.exception.DuplicateResourceException;
import com.prg.auth.exception.ResourceNotFoundException;
import com.prg.auth.repository.RoleRepository;
import com.prg.auth.repository.TenantRepository;
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
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<TenantResponse> getTenants(UserPrincipal principal) {
        List<Tenant> tenants;

        if (principal.hasScope("global")) {
            tenants = tenantRepository.findByIsActiveTrue();
        } else {
            tenants = tenantRepository.findById(principal.getTenantId())
                    .filter(Tenant::getIsActive)
                    .map(List::of)
                    .orElse(List.of());
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
