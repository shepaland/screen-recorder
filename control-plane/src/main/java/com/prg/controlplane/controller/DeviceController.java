package com.prg.controlplane.controller;

import com.prg.controlplane.dto.request.AssignDeviceGroupRequest;
import com.prg.controlplane.dto.request.HeartbeatRequest;
import com.prg.controlplane.dto.request.UpdateDeviceRequest;
import com.prg.controlplane.dto.response.DeviceDetailResponse;
import com.prg.controlplane.dto.response.DeviceLogResponse;
import com.prg.controlplane.dto.response.DeviceResponse;
import com.prg.controlplane.dto.response.DeviceStatusLogResponse;
import com.prg.controlplane.dto.response.HeartbeatResponse;
import com.prg.controlplane.dto.response.PageResponse;
import com.prg.controlplane.entity.DeviceLog;
import com.prg.controlplane.entity.DeviceStatusLog;
import com.prg.controlplane.exception.AccessDeniedException;
import com.prg.controlplane.repository.DeviceLogRepository;
import com.prg.controlplane.repository.DeviceStatusLogRepository;
import com.prg.controlplane.security.DevicePrincipal;
import com.prg.controlplane.service.DeviceService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/devices")
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceService deviceService;
    private final com.prg.controlplane.service.DeviceGroupService deviceGroupService;
    private final DeviceStatusLogRepository statusLogRepository;
    private final DeviceLogRepository deviceLogRepository;

    @GetMapping
    public ResponseEntity<PageResponse<DeviceResponse>> getDevices(
            HttpServletRequest httpRequest,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(name = "include_deleted", defaultValue = "false") boolean includeDeleted,
            @RequestParam(name = "device_group_id", required = false) String deviceGroupId) {
        DevicePrincipal principal = getPrincipal(httpRequest);
        requirePermission(principal, "DEVICES:READ");

        PageResponse<DeviceResponse> response = deviceService.getDevices(
                principal.getTenantId(), status, search, includeDeleted, deviceGroupId, page, size);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<DeviceDetailResponse> getDevice(
            @PathVariable UUID id,
            HttpServletRequest httpRequest) {
        DevicePrincipal principal = getPrincipal(httpRequest);
        requirePermission(principal, "DEVICES:READ");

        DeviceDetailResponse response = deviceService.getDevice(id, principal.getTenantId());
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{id}")
    public ResponseEntity<DeviceResponse> updateDevice(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateDeviceRequest request,
            HttpServletRequest httpRequest) {
        DevicePrincipal principal = getPrincipal(httpRequest);
        requirePermission(principal, "DEVICES:UPDATE");

        DeviceResponse response = deviceService.updateDevice(id, request, principal.getTenantId());
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> softDeleteDevice(
            @PathVariable UUID id,
            HttpServletRequest httpRequest) {
        DevicePrincipal principal = getPrincipal(httpRequest);
        requirePermission(principal, "DEVICES:DELETE");

        deviceService.softDeleteDevice(id, principal.getTenantId(),
                principal.getUserId(), getClientIp(httpRequest),
                httpRequest.getHeader("User-Agent"));
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/restore")
    public ResponseEntity<DeviceResponse> restoreDevice(
            @PathVariable UUID id,
            HttpServletRequest httpRequest) {
        DevicePrincipal principal = getPrincipal(httpRequest);
        requirePermission(principal, "DEVICES:UPDATE");

        DeviceResponse response = deviceService.restoreDevice(id, principal.getTenantId());
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{id}/group")
    public ResponseEntity<DeviceResponse> assignDeviceGroup(
            @PathVariable UUID id,
            @RequestBody AssignDeviceGroupRequest request,
            HttpServletRequest httpRequest) {
        DevicePrincipal principal = getPrincipal(httpRequest);
        requirePermission(principal, "DEVICES:MANAGE");

        deviceGroupService.assignDeviceToGroup(principal.getTenantId(), id, request.getDeviceGroupId());
        DeviceResponse response = deviceService.getDeviceResponse(id, principal.getTenantId());
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{id}/heartbeat")
    public ResponseEntity<HeartbeatResponse> heartbeat(
            @PathVariable UUID id,
            @Valid @RequestBody HeartbeatRequest request,
            HttpServletRequest httpRequest) {
        DevicePrincipal principal = getPrincipal(httpRequest);

        // Device owner (principal.deviceId == id) OR user with DEVICES:UPDATE permission
        if (principal.getDeviceId() == null || !principal.getDeviceId().equals(id)) {
            if (!principal.hasPermission("DEVICES:UPDATE")) {
                throw new AccessDeniedException("Not authorized to send heartbeat for this device");
            }
        }

        String ipAddress = getClientIp(httpRequest);
        HeartbeatResponse response = deviceService.processHeartbeat(id, request, ipAddress, principal);
        return ResponseEntity.ok(response);
    }

    // ---- Device Logs (uploaded from agent) ----

    @GetMapping("/{id}/logs")
    public ResponseEntity<List<DeviceLogResponse>> getDeviceLogs(
            @PathVariable UUID id,
            HttpServletRequest httpRequest) {
        DevicePrincipal principal = getPrincipal(httpRequest);
        requirePermission(principal, "DEVICES:READ");

        List<DeviceLog> logs = deviceLogRepository.findByDeviceIdAndTenantIdOrderByLogTypeAsc(id, principal.getTenantId());
        var response = logs.stream()
                .map(log -> DeviceLogResponse.builder()
                        .id(log.getId())
                        .logType(log.getLogType())
                        .content(log.getContent())
                        .logFromTs(log.getLogFromTs())
                        .logToTs(log.getLogToTs())
                        .uploadedAt(log.getUploadedAt())
                        .build())
                .toList();
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/logs")
    @Transactional
    public ResponseEntity<Void> uploadDeviceLogs(
            @PathVariable UUID id,
            HttpServletRequest httpRequest,
            @RequestBody List<Map<String, String>> logEntries) {
        DevicePrincipal principal = getPrincipal(httpRequest);

        // Delete old logs for this device
        deviceLogRepository.deleteByDeviceIdAndTenantId(id, principal.getTenantId());

        // Save new logs
        for (Map<String, String> entry : logEntries) {
            String logType = entry.get("log_type");
            String content = entry.get("content");
            if (logType == null || content == null) continue;

            deviceLogRepository.save(DeviceLog.builder()
                    .tenantId(principal.getTenantId())
                    .deviceId(id)
                    .logType(logType)
                    .content(content)
                    .logFromTs(entry.containsKey("from_ts") ? Instant.parse(entry.get("from_ts")) : null)
                    .logToTs(entry.containsKey("to_ts") ? Instant.parse(entry.get("to_ts")) : null)
                    .uploadedAt(Instant.now())
                    .build());
        }

        return ResponseEntity.ok().build();
    }

    @GetMapping("/{id}/status-log")
    public ResponseEntity<PageResponse<DeviceStatusLogResponse>> getDeviceStatusLog(
            @PathVariable UUID id,
            HttpServletRequest httpRequest,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        DevicePrincipal principal = getPrincipal(httpRequest);
        requirePermission(principal, "DEVICES:READ");

        Page<DeviceStatusLog> logPage = statusLogRepository.findByDeviceIdAndTenantIdWithDateRange(
                id, principal.getTenantId(), null, null, PageRequest.of(page, size));

        var content = logPage.getContent().stream()
                .map(log -> DeviceStatusLogResponse.builder()
                        .id(log.getId())
                        .deviceId(log.getDeviceId())
                        .previousStatus(log.getPreviousStatus())
                        .newStatus(log.getNewStatus())
                        .changedTs(log.getChangedTs())
                        .trigger(log.getTrigger())
                        .details(log.getDetails())
                        .build())
                .toList();

        return ResponseEntity.ok(PageResponse.<DeviceStatusLogResponse>builder()
                .content(content)
                .page(page)
                .size(size)
                .totalElements(logPage.getTotalElements())
                .totalPages(logPage.getTotalPages())
                .build());
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

    private String getClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isEmpty()) return xff.split(",")[0].trim();
        return request.getRemoteAddr();
    }
}
