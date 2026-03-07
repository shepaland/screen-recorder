package com.prg.controlplane.service;

import com.prg.controlplane.dto.request.HeartbeatRequest;
import com.prg.controlplane.dto.request.UpdateDeviceRequest;
import com.prg.controlplane.dto.response.*;
import com.prg.controlplane.entity.Device;
import com.prg.controlplane.entity.DeviceCommand;
import com.prg.controlplane.exception.AccessDeniedException;
import com.prg.controlplane.exception.ResourceNotFoundException;
import com.prg.controlplane.repository.DeviceCommandRepository;
import com.prg.controlplane.repository.DeviceRepository;
import com.prg.controlplane.repository.RecordingSessionRepository;
import com.prg.controlplane.security.DevicePrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeviceService {

    private final DeviceRepository deviceRepository;
    private final DeviceCommandRepository deviceCommandRepository;
    private final RecordingSessionRepository recordingSessionRepository;

    @Value("${prg.device.default-heartbeat-interval-sec:30}")
    private int defaultHeartbeatIntervalSec;

    @Transactional(readOnly = true)
    public PageResponse<DeviceResponse> getDevices(UUID tenantId, String status, String search,
                                                     boolean includeDeleted, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, Math.min(size, 100));

        Page<Device> devicePage = deviceRepository.findByTenantIdWithFilters(
                tenantId, status, search, includeDeleted, pageRequest);

        return PageResponse.<DeviceResponse>builder()
                .content(devicePage.getContent().stream().map(this::toResponse).toList())
                .page(devicePage.getNumber())
                .size(devicePage.getSize())
                .totalElements(devicePage.getTotalElements())
                .totalPages(devicePage.getTotalPages())
                .build();
    }

    @Transactional(readOnly = true)
    public DeviceDetailResponse getDevice(UUID deviceId, UUID tenantId) {
        Device device = findDeviceByIdAndTenant(deviceId, tenantId);

        List<DeviceCommand> recentCommands = deviceCommandRepository
                .findRecentByDeviceIdAndTenantId(deviceId, tenantId, 10);

        return toDetailResponse(device, recentCommands);
    }

    @Transactional
    public DeviceResponse updateDevice(UUID deviceId, UpdateDeviceRequest request, UUID tenantId) {
        Device device = findDeviceByIdAndTenant(deviceId, tenantId);

        if (request.getSettings() != null) {
            device.setSettings(request.getSettings());
        }
        if (request.getIsActive() != null) {
            device.setIsActive(request.getIsActive());
            if (!request.getIsActive()) {
                device.setStatus("offline");
            }
        }

        device = deviceRepository.save(device);
        log.info("Device updated: id={}, tenant_id={}", device.getId(), tenantId);
        return toResponse(device);
    }

    @Transactional
    public void softDeleteDevice(UUID deviceId, UUID tenantId, UUID userId,
                                  String ipAddress, String userAgent) {
        Device device = findDeviceByIdAndTenant(deviceId, tenantId);

        // Idempotent: if already deleted, do nothing
        if (Boolean.TRUE.equals(device.getIsDeleted())) {
            log.info("Device already soft-deleted: id={}, tenant_id={}", deviceId, tenantId);
            return;
        }

        Instant now = Instant.now();

        // 1. Interrupt active recording sessions for this device
        int interruptedSessions = recordingSessionRepository
                .interruptActiveSessionsByDeviceId(deviceId, tenantId, now);
        if (interruptedSessions > 0) {
            log.info("Interrupted {} active recording sessions for device being soft-deleted: device_id={}, tenant_id={}",
                    interruptedSessions, deviceId, tenantId);
        }

        // 2. Expire pending commands that can no longer be delivered
        int expiredCommands = deviceCommandRepository
                .expirePendingCommandsByDeviceId(deviceId, tenantId);
        if (expiredCommands > 0) {
            log.info("Expired {} pending commands for device being soft-deleted: device_id={}, tenant_id={}",
                    expiredCommands, deviceId, tenantId);
        }

        // 3. Soft delete the device
        device.setIsDeleted(true);
        device.setDeletedTs(now);
        device.setIsActive(false);
        device.setStatus("offline");
        deviceRepository.save(device);

        log.info("DEVICE_SOFT_DELETED: id={}, hostname={}, tenant_id={}, user_id={}, ip={}, interrupted_sessions={}, expired_commands={}",
                device.getId(), device.getHostname(), tenantId, userId, ipAddress,
                interruptedSessions, expiredCommands);
    }

    @Transactional
    public DeviceResponse restoreDevice(UUID deviceId, UUID tenantId) {
        Device device = findDeviceByIdAndTenant(deviceId, tenantId);

        // Idempotent: if not deleted, return current state
        if (!Boolean.TRUE.equals(device.getIsDeleted())) {
            log.info("Device is not soft-deleted, returning current state: id={}, tenant_id={}", deviceId, tenantId);
            return toResponse(device);
        }

        device.setIsDeleted(false);
        device.setDeletedTs(null);
        device.setIsActive(true);
        device = deviceRepository.save(device);

        log.info("DEVICE_RESTORED: id={}, hostname={}, tenant_id={}", device.getId(), device.getHostname(), tenantId);
        return toResponse(device);
    }

    @Transactional
    public HeartbeatResponse processHeartbeat(UUID deviceId, HeartbeatRequest request,
                                               String ipAddress, DevicePrincipal principal) {
        Device device = findDeviceByIdAndTenant(deviceId, principal.getTenantId());

        // Verify device ownership: if principal has a deviceId, it must match
        if (principal.getDeviceId() != null && !principal.getDeviceId().equals(deviceId)) {
            if (!principal.hasPermission("DEVICES:UPDATE")) {
                log.warn("Heartbeat device mismatch: principal.deviceId={}, requested deviceId={}, user={}",
                        principal.getDeviceId(), deviceId, principal.getUserId());
                throw new AccessDeniedException("Device ID mismatch - cannot send heartbeat for another device");
            }
        }

        // Auto-restore soft-deleted device on heartbeat
        if (Boolean.TRUE.equals(device.getIsDeleted())) {
            device.setIsDeleted(false);
            device.setDeletedTs(null);
            device.setIsActive(true);
            log.info("DEVICE_AUTO_RESTORED: device_id={}, tenant_id={}, trigger=heartbeat",
                    deviceId, principal.getTenantId());
        }

        // Update device fields
        device.setStatus(request.getStatus());
        device.setLastHeartbeatTs(Instant.now());
        device.setIpAddress(ipAddress);
        if (request.getAgentVersion() != null) {
            device.setAgentVersion(request.getAgentVersion());
        }
        if (request.getTimezone() != null) {
            device.setTimezone(request.getTimezone());
        }
        if (request.getOsType() != null) {
            device.setOsType(request.getOsType());
        }
        if ("recording".equals(request.getStatus())) {
            device.setLastRecordingTs(Instant.now());
        }

        deviceRepository.save(device);

        // Find and deliver pending commands
        List<DeviceCommand> pendingCommands = deviceCommandRepository
                .findPendingCommandsByDeviceIdAndTenantId(deviceId, principal.getTenantId(), Instant.now());

        // Mark commands as delivered
        Instant now = Instant.now();
        for (DeviceCommand cmd : pendingCommands) {
            cmd.setStatus("delivered");
            cmd.setDeliveredTs(now);
        }
        if (!pendingCommands.isEmpty()) {
            deviceCommandRepository.saveAll(pendingCommands);
            log.info("Delivered {} commands to device: id={}", pendingCommands.size(), deviceId);
        }

        log.debug("Heartbeat processed: device_id={}, status={}, commands_delivered={}",
                deviceId, request.getStatus(), pendingCommands.size());

        // Include device settings in response if present
        Map<String, Object> deviceSettings = device.getSettings() != null && !device.getSettings().isEmpty()
                ? device.getSettings() : null;

        return HeartbeatResponse.builder()
                .serverTs(now)
                .pendingCommands(pendingCommands.stream().map(this::toCommandResponse).toList())
                .nextHeartbeatSec(defaultHeartbeatIntervalSec)
                .deviceSettings(deviceSettings)
                .build();
    }

    private Device findDeviceByIdAndTenant(UUID deviceId, UUID tenantId) {
        return deviceRepository.findByIdAndTenantId(deviceId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Device not found: " + deviceId, "DEVICE_NOT_FOUND"));
    }

    private DeviceResponse toResponse(Device device) {
        return DeviceResponse.builder()
                .id(device.getId())
                .tenantId(device.getTenantId())
                .userId(device.getUserId())
                .hostname(device.getHostname())
                .osVersion(device.getOsVersion())
                .osType(device.getOsType())
                .agentVersion(device.getAgentVersion())
                .status(device.getStatus())
                .lastHeartbeatTs(device.getLastHeartbeatTs())
                .lastRecordingTs(device.getLastRecordingTs())
                .ipAddress(device.getIpAddress())
                .timezone(device.getTimezone())
                .settings(device.getSettings())
                .isActive(device.getIsActive())
                .isDeleted(device.getIsDeleted())
                .deletedTs(device.getDeletedTs())
                .createdTs(device.getCreatedTs())
                .updatedTs(device.getUpdatedTs())
                .build();
    }

    private DeviceDetailResponse toDetailResponse(Device device, List<DeviceCommand> recentCommands) {
        return DeviceDetailResponse.builder()
                .id(device.getId())
                .tenantId(device.getTenantId())
                .userId(device.getUserId())
                .registrationTokenId(device.getRegistrationTokenId())
                .hostname(device.getHostname())
                .osVersion(device.getOsVersion())
                .osType(device.getOsType())
                .agentVersion(device.getAgentVersion())
                .hardwareId(device.getHardwareId())
                .status(device.getStatus())
                .lastHeartbeatTs(device.getLastHeartbeatTs())
                .lastRecordingTs(device.getLastRecordingTs())
                .ipAddress(device.getIpAddress())
                .timezone(device.getTimezone())
                .settings(device.getSettings())
                .isActive(device.getIsActive())
                .isDeleted(device.getIsDeleted())
                .deletedTs(device.getDeletedTs())
                .createdTs(device.getCreatedTs())
                .updatedTs(device.getUpdatedTs())
                .recentCommands(recentCommands.stream().map(this::toCommandResponse).toList())
                .build();
    }

    private CommandResponse toCommandResponse(DeviceCommand cmd) {
        return CommandResponse.builder()
                .id(cmd.getId())
                .deviceId(cmd.getDeviceId())
                .commandType(cmd.getCommandType())
                .payload(cmd.getPayload())
                .status(cmd.getStatus())
                .createdBy(cmd.getCreatedBy())
                .deliveredTs(cmd.getDeliveredTs())
                .acknowledgedTs(cmd.getAcknowledgedTs())
                .result(cmd.getResult())
                .expiresAt(cmd.getExpiresAt())
                .createdTs(cmd.getCreatedTs())
                .build();
    }
}
