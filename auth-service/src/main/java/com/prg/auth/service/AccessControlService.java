package com.prg.auth.service;

import com.prg.auth.dto.request.CheckAccessRequest;
import com.prg.auth.dto.response.CheckAccessResponse;
import com.prg.auth.entity.User;
import com.prg.auth.exception.ResourceNotFoundException;
import com.prg.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class AccessControlService {

    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public CheckAccessResponse checkAccess(CheckAccessRequest request) {
        User user = userRepository.findByIdAndTenantId(request.getUserId(), request.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found", "USER_NOT_FOUND"));

        if (!user.getIsActive()) {
            return CheckAccessResponse.builder()
                    .allowed(false)
                    .reason("User account is disabled")
                    .build();
        }

        // Check if user has the required permission
        List<String> roles = user.getRoles().stream()
                .map(r -> r.getCode())
                .toList();

        boolean hasPermission = user.getRoles().stream()
                .flatMap(r -> r.getPermissions().stream())
                .anyMatch(p -> p.getCode().equals(request.getPermission()));

        if (!hasPermission) {
            return CheckAccessResponse.builder()
                    .allowed(false)
                    .reason("User does not have permission: " + request.getPermission())
                    .build();
        }

        // ABAC scope check
        if (roles.contains("SUPER_ADMIN")) {
            // Global scope: access everything
            return CheckAccessResponse.builder()
                    .allowed(true)
                    .reason("User has " + request.getPermission() + " permission with global scope")
                    .build();
        }

        // Tenant scope check (already passed via tenantId match in the query)
        if (roles.contains("OPERATOR")) {
            // Own scope: can only access own resources
            if (request.getResourceOwnerId() != null &&
                    !request.getResourceOwnerId().equals(request.getUserId())) {
                return CheckAccessResponse.builder()
                        .allowed(false)
                        .reason("OPERATOR can only access own " + request.getResourceType())
                        .build();
            }
        }

        return CheckAccessResponse.builder()
                .allowed(true)
                .reason("User has " + request.getPermission() + " permission with tenant scope")
                .build();
    }
}
