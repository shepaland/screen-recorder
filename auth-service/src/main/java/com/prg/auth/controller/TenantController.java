package com.prg.auth.controller;

import com.prg.auth.dto.request.CreateTenantRequest;
import com.prg.auth.dto.request.UpdateTenantRequest;
import com.prg.auth.dto.response.PageResponse;
import com.prg.auth.dto.response.TenantResponse;
import com.prg.auth.exception.AccessDeniedException;
import com.prg.auth.security.UserPrincipal;
import com.prg.auth.service.TenantService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants")
@RequiredArgsConstructor
public class TenantController {

    private final TenantService tenantService;

    @GetMapping
    public ResponseEntity<PageResponse<TenantResponse>> getTenants(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        requirePermission(principal, "TENANTS:READ");
        List<TenantResponse> tenants = tenantService.getTenants(principal);

        // Wrap list into PageResponse for frontend compatibility
        int fromIndex = Math.min(page * size, tenants.size());
        int toIndex = Math.min(fromIndex + size, tenants.size());
        List<TenantResponse> pageContent = tenants.subList(fromIndex, toIndex);

        PageResponse<TenantResponse> response = PageResponse.<TenantResponse>builder()
                .content(pageContent)
                .page(page)
                .size(size)
                .totalElements(tenants.size())
                .totalPages((int) Math.ceil((double) tenants.size() / size))
                .build();
        return ResponseEntity.ok(response);
    }

    @PostMapping
    public ResponseEntity<TenantResponse> createTenant(
            @Valid @RequestBody CreateTenantRequest request,
            @AuthenticationPrincipal UserPrincipal principal,
            HttpServletRequest httpRequest) {
        requirePermission(principal, "TENANTS:CREATE");
        TenantResponse response = tenantService.createTenant(
                request, principal,
                getClientIp(httpRequest), httpRequest.getHeader("User-Agent"));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}")
    public ResponseEntity<TenantResponse> updateTenant(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateTenantRequest request,
            @AuthenticationPrincipal UserPrincipal principal,
            HttpServletRequest httpRequest) {
        requirePermission(principal, "TENANTS:UPDATE");
        TenantResponse response = tenantService.updateTenant(
                id, request, principal,
                getClientIp(httpRequest), httpRequest.getHeader("User-Agent"));
        return ResponseEntity.ok(response);
    }

    private void requirePermission(UserPrincipal principal, String permission) {
        if (!principal.hasPermission(permission)) {
            throw new AccessDeniedException("You do not have permission: " + permission);
        }
    }

    private String getClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isEmpty()) return xff.split(",")[0].trim();
        return request.getRemoteAddr();
    }
}
