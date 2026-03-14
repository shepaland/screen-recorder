package com.prg.controlplane.controller;

import com.prg.controlplane.dto.request.BulkAssignDevicesRequest;
import com.prg.controlplane.dto.request.CreateDeviceGroupRequest;
import com.prg.controlplane.dto.request.UpdateDeviceGroupRequest;
import com.prg.controlplane.dto.response.BulkAssignDevicesResponse;
import com.prg.controlplane.dto.response.DeviceGroupResponse;
import com.prg.controlplane.exception.AccessDeniedException;
import com.prg.controlplane.security.DevicePrincipal;
import com.prg.controlplane.service.DeviceGroupService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/device-groups")
@RequiredArgsConstructor
public class DeviceGroupController {

    private final DeviceGroupService deviceGroupService;

    @GetMapping
    public ResponseEntity<List<DeviceGroupResponse>> getDeviceGroups(
            HttpServletRequest httpRequest,
            @RequestParam(name = "include_stats", defaultValue = "false") boolean includeStats) {
        DevicePrincipal principal = getPrincipal(httpRequest);
        requirePermission(principal, "DEVICES:READ");

        List<DeviceGroupResponse> groups = deviceGroupService.getGroups(principal.getTenantId(), includeStats);
        return ResponseEntity.ok(groups);
    }

    @PostMapping
    public ResponseEntity<DeviceGroupResponse> createDeviceGroup(
            @Valid @RequestBody CreateDeviceGroupRequest request,
            HttpServletRequest httpRequest) {
        DevicePrincipal principal = getPrincipal(httpRequest);
        requirePermission(principal, "DEVICES:MANAGE");

        DeviceGroupResponse response = deviceGroupService.createGroup(
                principal.getTenantId(), principal.getUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}")
    public ResponseEntity<DeviceGroupResponse> updateDeviceGroup(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateDeviceGroupRequest request,
            HttpServletRequest httpRequest) {
        DevicePrincipal principal = getPrincipal(httpRequest);
        requirePermission(principal, "DEVICES:MANAGE");

        DeviceGroupResponse response = deviceGroupService.updateGroup(principal.getTenantId(), id, request);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteDeviceGroup(
            @PathVariable UUID id,
            HttpServletRequest httpRequest) {
        DevicePrincipal principal = getPrincipal(httpRequest);
        requirePermission(principal, "DEVICES:MANAGE");

        deviceGroupService.deleteGroup(principal.getTenantId(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/devices")
    public ResponseEntity<BulkAssignDevicesResponse> bulkAssignDevices(
            @PathVariable UUID id,
            @Valid @RequestBody BulkAssignDevicesRequest request,
            HttpServletRequest httpRequest) {
        DevicePrincipal principal = getPrincipal(httpRequest);
        requirePermission(principal, "DEVICES:MANAGE");

        BulkAssignDevicesResponse response = deviceGroupService.bulkAssignDevices(
                principal.getTenantId(), id, request);
        return ResponseEntity.ok(response);
    }

    private DevicePrincipal getPrincipal(HttpServletRequest request) {
        DevicePrincipal principal = (DevicePrincipal) request.getAttribute("principal");
        if (principal == null) {
            throw new AccessDeniedException("Authentication required");
        }
        return principal;
    }

    private void requirePermission(DevicePrincipal principal, String permission) {
        if (!principal.hasPermission(permission)) {
            throw new AccessDeniedException("You do not have permission: " + permission);
        }
    }
}
