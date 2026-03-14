package com.prg.ingest.controller;

import com.prg.ingest.dto.employee.*;
import com.prg.ingest.exception.AccessDeniedException;
import com.prg.ingest.filter.JwtValidationFilter;
import com.prg.ingest.security.DevicePrincipal;
import com.prg.ingest.service.employee.EmployeeGroupService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ingest/employee-groups")
@RequiredArgsConstructor
@Slf4j
public class EmployeeGroupController {

    private static final String PERMISSION_EMPLOYEES_READ = "EMPLOYEES:READ";
    private static final String PERMISSION_EMPLOYEES_MANAGE = "EMPLOYEES:MANAGE";

    private final EmployeeGroupService employeeGroupService;

    @GetMapping
    public ResponseEntity<List<EmployeeGroupResponse>> getGroups(
            @RequestParam(name = "tenant_id", required = false) UUID tenantIdParam,
            HttpServletRequest httpRequest) {

        DevicePrincipal principal = getPrincipalWithPermission(httpRequest, PERMISSION_EMPLOYEES_READ);
        UUID tenantId = resolveEffectiveTenantId(principal, tenantIdParam);

        List<EmployeeGroupResponse> groups = employeeGroupService.getGroups(tenantId);
        return ResponseEntity.ok(groups);
    }

    @PostMapping
    public ResponseEntity<EmployeeGroupResponse> createGroup(
            @Valid @RequestBody EmployeeGroupCreateRequest request,
            @RequestParam(name = "tenant_id", required = false) UUID tenantIdParam,
            HttpServletRequest httpRequest) {

        DevicePrincipal principal = getPrincipalWithPermission(httpRequest, PERMISSION_EMPLOYEES_MANAGE);
        UUID tenantId = resolveEffectiveTenantId(principal, tenantIdParam);

        EmployeeGroupResponse response = employeeGroupService.createGroup(tenantId, principal.getUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{groupId}")
    public ResponseEntity<EmployeeGroupResponse> updateGroup(
            @PathVariable UUID groupId,
            @Valid @RequestBody EmployeeGroupUpdateRequest request,
            @RequestParam(name = "tenant_id", required = false) UUID tenantIdParam,
            HttpServletRequest httpRequest) {

        DevicePrincipal principal = getPrincipalWithPermission(httpRequest, PERMISSION_EMPLOYEES_MANAGE);
        UUID tenantId = resolveEffectiveTenantId(principal, tenantIdParam);

        EmployeeGroupResponse response = employeeGroupService.updateGroup(tenantId, groupId, request);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{groupId}")
    public ResponseEntity<Void> deleteGroup(
            @PathVariable UUID groupId,
            @RequestParam(name = "tenant_id", required = false) UUID tenantIdParam,
            HttpServletRequest httpRequest) {

        DevicePrincipal principal = getPrincipalWithPermission(httpRequest, PERMISSION_EMPLOYEES_MANAGE);
        UUID tenantId = resolveEffectiveTenantId(principal, tenantIdParam);

        employeeGroupService.deleteGroup(tenantId, groupId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{groupId}/members")
    public ResponseEntity<List<EmployeeGroupMemberResponse>> getMembers(
            @PathVariable UUID groupId,
            @RequestParam(name = "tenant_id", required = false) UUID tenantIdParam,
            HttpServletRequest httpRequest) {

        DevicePrincipal principal = getPrincipalWithPermission(httpRequest, PERMISSION_EMPLOYEES_READ);
        UUID tenantId = resolveEffectiveTenantId(principal, tenantIdParam);

        List<EmployeeGroupMemberResponse> members = employeeGroupService.getMembers(tenantId, groupId);
        return ResponseEntity.ok(members);
    }

    @PostMapping("/{groupId}/members")
    public ResponseEntity<EmployeeGroupMemberResponse> addMember(
            @PathVariable UUID groupId,
            @Valid @RequestBody AssignEmployeeRequest request,
            @RequestParam(name = "tenant_id", required = false) UUID tenantIdParam,
            HttpServletRequest httpRequest) {

        DevicePrincipal principal = getPrincipalWithPermission(httpRequest, PERMISSION_EMPLOYEES_MANAGE);
        UUID tenantId = resolveEffectiveTenantId(principal, tenantIdParam);

        EmployeeGroupMemberResponse response = employeeGroupService.addMember(tenantId, groupId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @DeleteMapping("/members/{memberId}")
    public ResponseEntity<Void> removeMember(
            @PathVariable UUID memberId,
            @RequestParam(name = "tenant_id", required = false) UUID tenantIdParam,
            HttpServletRequest httpRequest) {

        DevicePrincipal principal = getPrincipalWithPermission(httpRequest, PERMISSION_EMPLOYEES_MANAGE);
        UUID tenantId = resolveEffectiveTenantId(principal, tenantIdParam);

        employeeGroupService.removeMember(tenantId, memberId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/metrics")
    public ResponseEntity<GroupMetricsResponse> getGroupMetrics(
            @RequestParam(name = "group_id", required = false) UUID groupId,
            @RequestParam(name = "tenant_id", required = false) UUID tenantIdParam,
            HttpServletRequest httpRequest) {

        DevicePrincipal principal = getPrincipalWithPermission(httpRequest, PERMISSION_EMPLOYEES_READ);
        UUID tenantId = resolveEffectiveTenantId(principal, tenantIdParam);

        GroupMetricsResponse response = employeeGroupService.getGroupMetrics(tenantId, groupId);
        return ResponseEntity.ok(response);
    }

    // ---- Security helpers ----

    private DevicePrincipal getPrincipal(HttpServletRequest request) {
        DevicePrincipal principal = (DevicePrincipal) request.getAttribute(
                JwtValidationFilter.DEVICE_PRINCIPAL_ATTRIBUTE);
        if (principal == null) {
            throw new IllegalStateException("DevicePrincipal not found in request attributes");
        }
        return principal;
    }

    private DevicePrincipal getPrincipalWithPermission(HttpServletRequest request, String permission) {
        DevicePrincipal principal = getPrincipal(request);
        if (!principal.hasPermission(permission)) {
            throw new AccessDeniedException(
                    "Permission " + permission + " is required",
                    "INSUFFICIENT_PERMISSIONS");
        }
        return principal;
    }

    private UUID resolveEffectiveTenantId(DevicePrincipal principal, UUID tenantIdParam) {
        if (principal.hasScope("global")) {
            if (tenantIdParam == null) {
                throw new IllegalArgumentException("tenant_id is required for global scope. Specify tenant_id query parameter.");
            }
            return tenantIdParam;
        }
        return principal.getTenantId();
    }
}
