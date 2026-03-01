package com.prg.auth.service;

import com.prg.auth.dto.request.CreateRoleRequest;
import com.prg.auth.dto.request.UpdateRoleRequest;
import com.prg.auth.dto.response.PageResponse;
import com.prg.auth.dto.response.PermissionResponse;
import com.prg.auth.dto.response.RoleResponse;
import com.prg.auth.entity.Permission;
import com.prg.auth.entity.Role;
import com.prg.auth.entity.Tenant;
import com.prg.auth.exception.DuplicateResourceException;
import com.prg.auth.exception.ResourceNotFoundException;
import com.prg.auth.repository.PermissionRepository;
import com.prg.auth.repository.RoleRepository;
import com.prg.auth.repository.TenantRepository;
import com.prg.auth.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class RoleService {

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final TenantRepository tenantRepository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public PageResponse<RoleResponse> getRoles(UUID tenantId, Boolean isSystem, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, Math.min(size, 100), Sort.by(Sort.Direction.ASC, "code"));
        Page<Role> rolePage = roleRepository.findByTenantIdWithFilters(tenantId, isSystem, pageRequest);

        return PageResponse.<RoleResponse>builder()
                .content(rolePage.getContent().stream().map(this::toListResponse).toList())
                .page(rolePage.getNumber())
                .size(rolePage.getSize())
                .totalElements(rolePage.getTotalElements())
                .totalPages(rolePage.getTotalPages())
                .build();
    }

    @Transactional(readOnly = true)
    public RoleResponse getRoleById(UUID roleId, UUID tenantId) {
        Role role = roleRepository.findByIdAndTenantIdWithPermissions(roleId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Role not found", "ROLE_NOT_FOUND"));
        return toDetailResponse(role);
    }

    @Transactional
    public RoleResponse createRole(CreateRoleRequest request, UUID tenantId, UserPrincipal principal,
                                    String ipAddress, String userAgent) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found", "TENANT_NOT_FOUND"));

        if (roleRepository.existsByTenantIdAndCode(tenantId, request.getCode())) {
            throw new DuplicateResourceException("Role code already exists in this tenant", "ROLE_CODE_ALREADY_EXISTS");
        }

        // Resolve permissions
        List<Permission> permissions = permissionRepository.findByIdIn(request.getPermissionIds());
        if (permissions.size() != request.getPermissionIds().size()) {
            throw new ResourceNotFoundException("One or more permissions not found", "PERMISSION_NOT_FOUND");
        }

        Role role = Role.builder()
                .tenant(tenant)
                .code(request.getCode())
                .name(request.getName())
                .description(request.getDescription())
                .isSystem(false)
                .permissions(new HashSet<>(permissions))
                .build();

        role = roleRepository.save(role);

        // Audit
        auditService.logAction(tenantId, principal.getUserId(), "ROLE_CREATED", "ROLES", role.getId(),
                Map.of("code", role.getCode(), "permissions_count", permissions.size()),
                ipAddress, userAgent, null);

        log.info("Role created: id={}, code={}, tenant_id={}", role.getId(), role.getCode(), tenantId);
        return toDetailResponse(role);
    }

    @Transactional
    public RoleResponse updateRole(UUID roleId, UpdateRoleRequest request, UUID tenantId,
                                    UserPrincipal principal, String ipAddress, String userAgent) {
        Role role = roleRepository.findByIdAndTenantIdWithPermissions(roleId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Role not found", "ROLE_NOT_FOUND"));

        if (role.getIsSystem()) {
            throw new IllegalArgumentException("System roles cannot be modified");
        }

        Map<String, Object> changes = new HashMap<>();

        if (request.getName() != null) {
            changes.put("name", request.getName());
            role.setName(request.getName());
        }
        if (request.getDescription() != null) {
            changes.put("description", request.getDescription());
            role.setDescription(request.getDescription());
        }
        if (request.getPermissionIds() != null) {
            List<Permission> permissions = permissionRepository.findByIdIn(request.getPermissionIds());
            if (permissions.size() != request.getPermissionIds().size()) {
                throw new ResourceNotFoundException("One or more permissions not found", "PERMISSION_NOT_FOUND");
            }
            changes.put("permissions_count", permissions.size());
            role.setPermissions(new HashSet<>(permissions));
        }

        role = roleRepository.save(role);

        // Audit
        auditService.logAction(tenantId, principal.getUserId(), "ROLE_UPDATED", "ROLES", role.getId(),
                changes, ipAddress, userAgent, null);

        log.info("Role updated: id={}, code={}, tenant_id={}", role.getId(), role.getCode(), tenantId);
        return toDetailResponse(role);
    }

    @Transactional
    public void deleteRole(UUID roleId, UUID tenantId, UserPrincipal principal,
                            String ipAddress, String userAgent) {
        Role role = roleRepository.findByIdAndTenantId(roleId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Role not found", "ROLE_NOT_FOUND"));

        if (role.getIsSystem()) {
            throw new IllegalArgumentException("System roles cannot be deleted");
        }

        long usersCount = roleRepository.countUsersByRoleId(roleId);
        if (usersCount > 0) {
            throw new DuplicateResourceException(
                    "Cannot delete role with assigned users. Remove users first.", "ROLE_HAS_USERS");
        }

        roleRepository.delete(role);

        // Audit
        auditService.logAction(tenantId, principal.getUserId(), "ROLE_DELETED", "ROLES", roleId,
                Map.of("code", role.getCode()), ipAddress, userAgent, null);

        log.info("Role deleted: id={}, code={}, tenant_id={}", roleId, role.getCode(), tenantId);
    }

    private RoleResponse toListResponse(Role role) {
        return RoleResponse.builder()
                .id(role.getId())
                .code(role.getCode())
                .name(role.getName())
                .description(role.getDescription())
                .isSystem(role.getIsSystem())
                .permissionsCount(role.getPermissions() != null ? role.getPermissions().size() : 0)
                .usersCount(roleRepository.countUsersByRoleId(role.getId()))
                .createdTs(role.getCreatedTs())
                .updatedTs(role.getUpdatedTs())
                .build();
    }

    private RoleResponse toDetailResponse(Role role) {
        List<PermissionResponse> permissionResponses = role.getPermissions().stream()
                .map(p -> PermissionResponse.builder()
                        .id(p.getId())
                        .code(p.getCode())
                        .name(p.getName())
                        .resource(p.getResource())
                        .action(p.getAction())
                        .build())
                .sorted(Comparator.comparing(PermissionResponse::getResource)
                        .thenComparing(PermissionResponse::getAction))
                .toList();

        return RoleResponse.builder()
                .id(role.getId())
                .code(role.getCode())
                .name(role.getName())
                .description(role.getDescription())
                .isSystem(role.getIsSystem())
                .permissions(permissionResponses)
                .permissionsCount(permissionResponses.size())
                .usersCount(roleRepository.countUsersByRoleId(role.getId()))
                .createdTs(role.getCreatedTs())
                .updatedTs(role.getUpdatedTs())
                .build();
    }
}
