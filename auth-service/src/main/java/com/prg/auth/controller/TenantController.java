package com.prg.auth.controller;

import com.prg.auth.dto.request.CreateOwnTenantRequest;
import com.prg.auth.dto.request.CreateTenantRequest;
import com.prg.auth.dto.request.TransferOwnershipRequest;
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
import java.util.Map;
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
        // Allow all authenticated users to list tenants they have access to
        // The service method already filters by scope (global vs own tenant)
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

    /**
     * Create a new tenant owned by the current authenticated user.
     * No admin user details required - the current user becomes the owner.
     * Any authenticated user can call this endpoint.
     */
    @PostMapping("/create-own")
    public ResponseEntity<TenantResponse> createOwnTenant(
            @Valid @RequestBody CreateOwnTenantRequest request,
            @AuthenticationPrincipal UserPrincipal principal,
            HttpServletRequest httpRequest) {
        // No permission check - any authenticated user can create their own tenant
        TenantResponse response = tenantService.createOwnTenant(
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

    /**
     * POST /api/v1/tenants/{id}/transfer-ownership
     * Transfer tenant ownership to another user. Only OWNER or SUPER_ADMIN can call.
     */
    @PostMapping("/{id}/transfer-ownership")
    public ResponseEntity<Map<String, String>> transferOwnership(
            @PathVariable UUID id,
            @Valid @RequestBody TransferOwnershipRequest request,
            @AuthenticationPrincipal UserPrincipal principal,
            HttpServletRequest httpRequest) {
        // Only OWNER or SUPER_ADMIN can transfer ownership
        if (!principal.hasRole("OWNER") && !principal.hasRole("SUPER_ADMIN")) {
            throw new AccessDeniedException("Only the tenant owner or super admin can transfer ownership");
        }

        // TENANT_ADMIN scope check: can only transfer their own tenant
        if (!principal.hasScope("global") && !principal.getTenantId().equals(id)) {
            throw new AccessDeniedException("You can only transfer ownership of your own tenant");
        }

        tenantService.transferOwnership(id, request.getNewOwnerUserId(), principal,
                getClientIp(httpRequest), httpRequest.getHeader("User-Agent"));

        return ResponseEntity.ok(Map.of("message", "Ownership transferred successfully"));
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
