package com.prg.auth.controller;

import com.prg.auth.dto.response.PermissionResponse;
import com.prg.auth.exception.AccessDeniedException;
import com.prg.auth.security.UserPrincipal;
import com.prg.auth.service.PermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/permissions")
@RequiredArgsConstructor
public class PermissionController {

    private final PermissionService permissionService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> getAllPermissions(
            @AuthenticationPrincipal UserPrincipal principal) {
        if (!principal.hasPermission("ROLES:READ")) {
            throw new AccessDeniedException("You do not have permission: ROLES:READ");
        }
        List<PermissionResponse> permissions = permissionService.getAllPermissions();
        return ResponseEntity.ok(Map.of(
                "content", permissions,
                "total_elements", permissions.size()
        ));
    }
}
