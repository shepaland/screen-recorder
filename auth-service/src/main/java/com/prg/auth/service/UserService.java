package com.prg.auth.service;

import com.prg.auth.dto.request.ChangePasswordRequest;
import com.prg.auth.dto.request.CreateUserRequest;
import com.prg.auth.dto.request.UpdateUserRequest;
import com.prg.auth.dto.response.PageResponse;
import com.prg.auth.dto.response.RoleResponse;
import com.prg.auth.dto.response.UserResponse;
import com.prg.auth.entity.Role;
import com.prg.auth.entity.Tenant;
import com.prg.auth.entity.User;
import com.prg.auth.exception.AccessDeniedException;
import com.prg.auth.exception.DuplicateResourceException;
import com.prg.auth.exception.InvalidCredentialsException;
import com.prg.auth.exception.ResourceNotFoundException;
import com.prg.auth.repository.RoleRepository;
import com.prg.auth.repository.TenantRepository;
import com.prg.auth.repository.UserRepository;
import com.prg.auth.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final TenantRepository tenantRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public PageResponse<UserResponse> getUsers(UUID tenantId, String search, Boolean isActive,
                                                int page, int size, String sort) {
        Sort sortObj = parseSort(sort);
        PageRequest pageRequest = PageRequest.of(page, Math.min(size, 100), sortObj);

        Page<User> userPage = userRepository.findByTenantIdWithFilters(tenantId, search, isActive, pageRequest);

        return PageResponse.<UserResponse>builder()
                .content(userPage.getContent().stream().map(this::toResponse).toList())
                .page(userPage.getNumber())
                .size(userPage.getSize())
                .totalElements(userPage.getTotalElements())
                .totalPages(userPage.getTotalPages())
                .build();
    }

    @Transactional(readOnly = true)
    public UserResponse getUserById(UUID userId, UUID tenantId) {
        User user = userRepository.findByIdAndTenantId(userId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found", "USER_NOT_FOUND"));
        return toResponse(user);
    }

    @Transactional(readOnly = true)
    public UserResponse getCurrentUser(UUID userId, UUID tenantId) {
        User user = userRepository.findByIdAndTenantId(userId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found", "USER_NOT_FOUND"));
        return toResponse(user);
    }

    @Transactional
    public UserResponse createUser(CreateUserRequest request, UUID tenantId, UserPrincipal principal,
                                    String ipAddress, String userAgent) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found", "TENANT_NOT_FOUND"));

        // Check uniqueness
        if (userRepository.existsByTenantIdAndUsername(tenantId, request.getUsername())) {
            throw new DuplicateResourceException("Username already exists in this tenant", "USERNAME_ALREADY_EXISTS");
        }
        if (userRepository.existsByTenantIdAndEmail(tenantId, request.getEmail())) {
            throw new DuplicateResourceException("Email already exists in this tenant", "EMAIL_ALREADY_EXISTS");
        }

        // Resolve roles
        Set<Role> roles = new HashSet<>();
        if (request.getRoleIds() != null && !request.getRoleIds().isEmpty()) {
            for (UUID roleId : request.getRoleIds()) {
                Role role = roleRepository.findByIdAndTenantId(roleId, tenantId)
                        .orElseThrow(() -> new ResourceNotFoundException("Role not found: " + roleId, "ROLE_NOT_FOUND"));
                roles.add(role);
            }
        }

        User user = User.builder()
                .tenant(tenant)
                .username(request.getUsername())
                .email(request.getEmail())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .isActive(true)
                .roles(roles)
                .build();

        user = userRepository.save(user);

        // Audit
        auditService.logAction(tenantId, principal.getUserId(), "USER_CREATED", "USERS", user.getId(),
                Map.of("username", user.getUsername(), "roles", roles.stream().map(Role::getCode).toList()),
                ipAddress, userAgent, null);

        log.info("User created: id={}, username={}, tenant_id={}", user.getId(), user.getUsername(), tenantId);
        return toResponse(user);
    }

    @Transactional
    public UserResponse updateUser(UUID userId, UpdateUserRequest request, UUID tenantId,
                                    UserPrincipal principal, String ipAddress, String userAgent) {
        User user = userRepository.findByIdAndTenantId(userId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found", "USER_NOT_FOUND"));

        Map<String, Object> changes = new HashMap<>();

        if (request.getEmail() != null) {
            if (!request.getEmail().equals(user.getEmail()) &&
                    userRepository.existsByTenantIdAndEmail(tenantId, request.getEmail())) {
                throw new DuplicateResourceException("Email already exists in this tenant", "EMAIL_ALREADY_EXISTS");
            }
            changes.put("email", request.getEmail());
            user.setEmail(request.getEmail());
        }
        if (request.getFirstName() != null) {
            changes.put("first_name", request.getFirstName());
            user.setFirstName(request.getFirstName());
        }
        if (request.getLastName() != null) {
            changes.put("last_name", request.getLastName());
            user.setLastName(request.getLastName());
        }
        if (request.getIsActive() != null) {
            changes.put("is_active", request.getIsActive());
            user.setIsActive(request.getIsActive());
        }
        if (request.getRoleIds() != null) {
            Set<Role> roles = new HashSet<>();
            for (UUID roleId : request.getRoleIds()) {
                Role role = roleRepository.findByIdAndTenantId(roleId, tenantId)
                        .orElseThrow(() -> new ResourceNotFoundException("Role not found: " + roleId, "ROLE_NOT_FOUND"));
                roles.add(role);
            }
            changes.put("roles", roles.stream().map(Role::getCode).toList());
            user.setRoles(roles);
        }

        user = userRepository.save(user);

        // Audit
        auditService.logAction(tenantId, principal.getUserId(), "USER_UPDATED", "USERS", user.getId(),
                changes, ipAddress, userAgent, null);

        log.info("User updated: id={}, tenant_id={}", user.getId(), tenantId);
        return toResponse(user);
    }

    @Transactional
    public void deactivateUser(UUID userId, UUID tenantId, UserPrincipal principal,
                                String ipAddress, String userAgent) {
        User user = userRepository.findByIdAndTenantId(userId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found", "USER_NOT_FOUND"));

        user.setIsActive(false);
        userRepository.save(user);

        // Audit
        auditService.logAction(tenantId, principal.getUserId(), "USER_DEACTIVATED", "USERS", user.getId(),
                Map.of("username", user.getUsername()), ipAddress, userAgent, null);

        log.info("User deactivated: id={}, username={}, tenant_id={}", user.getId(), user.getUsername(), tenantId);
    }

    @Transactional
    public void changePassword(UUID userId, ChangePasswordRequest request, UUID tenantId,
                                UserPrincipal principal, String ipAddress, String userAgent) {
        User user = userRepository.findByIdAndTenantId(userId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found", "USER_NOT_FOUND"));

        boolean isSelf = principal.getUserId().equals(userId);
        boolean isAdmin = principal.hasPermission("USERS:UPDATE");

        if (isSelf) {
            // Self password change requires current password
            if (request.getCurrentPassword() == null || request.getCurrentPassword().isBlank()) {
                throw new InvalidCredentialsException("Current password is required for self password change");
            }
            if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPasswordHash())) {
                throw new InvalidCredentialsException("Current password is incorrect");
            }
        } else if (!isAdmin) {
            throw new AccessDeniedException("You do not have permission to change another user's password");
        }

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);

        // Audit
        auditService.logAction(tenantId, principal.getUserId(), "USER_PASSWORD_CHANGED", "USERS", user.getId(),
                Map.of("changed_by", isSelf ? "self" : "admin"), ipAddress, userAgent, null);

        log.info("Password changed: user_id={}, changed_by={}", userId, isSelf ? "self" : principal.getUserId());
    }

    private UserResponse toResponse(User user) {
        List<RoleResponse> roleResponses = user.getRoles().stream()
                .map(r -> RoleResponse.builder()
                        .id(r.getId())
                        .code(r.getCode())
                        .name(r.getName())
                        .build())
                .toList();

        List<String> permissions = user.getRoles().stream()
                .flatMap(r -> r.getPermissions().stream())
                .map(p -> p.getCode())
                .distinct()
                .sorted()
                .collect(Collectors.toList());

        return UserResponse.builder()
                .id(user.getId())
                .tenantId(user.getTenant().getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .isActive(user.getIsActive())
                .roles(roleResponses)
                .permissions(permissions)
                .lastLoginTs(user.getLastLoginTs())
                .createdTs(user.getCreatedTs())
                .updatedTs(user.getUpdatedTs())
                .build();
    }

    private static final Map<String, String> SORT_FIELD_MAP = Map.of(
            "created_ts", "createdTs",
            "updated_ts", "updatedTs",
            "last_login_ts", "lastLoginTs",
            "username", "username",
            "email", "email",
            "first_name", "firstName",
            "last_name", "lastName",
            "is_active", "isActive"
    );

    private Sort parseSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return Sort.by(Sort.Direction.DESC, "createdTs");
        }
        String[] parts = sort.split(",");
        String field = SORT_FIELD_MAP.getOrDefault(parts[0].trim(), "createdTs");
        Sort.Direction direction = parts.length > 1 && parts[1].trim().equalsIgnoreCase("asc")
                ? Sort.Direction.ASC : Sort.Direction.DESC;
        return Sort.by(direction, field);
    }
}
