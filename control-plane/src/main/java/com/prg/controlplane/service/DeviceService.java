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
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeviceService {

    private final DeviceRepository deviceRepository;
    private final DeviceCommandRepository deviceCommandRepository;

    @Value("${prg.device.default-heartbeat-interval-sec:30}")
    private int defaultHeartbeatIntervalSec;

    @Transactional(readOnly = true)
    public PageResponse<DeviceResponse> getDevices(UUID tenantId, String status, String search, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, Math.min(size, 100));

        Page<Device> devicePage = deviceRepository.findByTenantIdWithFilters(tenantId, status, search, pageRequest);

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
    public void deactivateDevice(UUID deviceId, UUID tenantId) {
        Device device = findDeviceByIdAndTenant(deviceId, tenantId);

        device.setIsActive(false);
        device.setStatus("offline");
        deviceRepository.save(device);

        log.info("Device deactivated: id={}, hostname={}, tenant_id={}", device.getId(), device.getHostname(), tenantId);
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

        // Update device fields
        device.setStatus(request.getStatus());
        device.setLastHeartbeatTs(Instant.now());
        device.setIpAddress(ipAddress);
        if (request.getAgentVersion() != null) {
            device.setAgentVersion(request.getAgentVersion());
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

        return HeartbeatResponse.builder()
                .serverTs(now)
                .pendingCommands(pendingCommands.stream().map(this::toCommandResponse).toList())
                .nextHeartbeatSec(defaultHeartbeatIntervalSec)
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
                .agentVersion(device.getAgentVersion())
                .status(device.getStatus())
                .lastHeartbeatTs(device.getLastHeartbeatTs())
                .lastRecordingTs(device.getLastRecordingTs())
                .ipAddress(device.getIpAddress())
                .settings(device.getSettings())
                .isActive(device.getIsActive())
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
                .agentVersion(device.getAgentVersion())
                .hardwareId(device.getHardwareId())
                .status(device.getStatus())
                .lastHeartbeatTs(device.getLastHeartbeatTs())
                .lastRecordingTs(device.getLastRecordingTs())
                .ipAddress(device.getIpAddress())
                .settings(device.getSettings())
                .isActive(device.getIsActive())
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
