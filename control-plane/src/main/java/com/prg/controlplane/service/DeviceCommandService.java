package com.prg.controlplane.service;

import com.prg.controlplane.dto.request.AckCommandRequest;
import com.prg.controlplane.dto.request.CreateCommandRequest;
import com.prg.controlplane.dto.response.CommandResponse;
import com.prg.controlplane.dto.response.PageResponse;
import com.prg.controlplane.entity.Device;
import com.prg.controlplane.entity.DeviceCommand;
import com.prg.controlplane.exception.AccessDeniedException;
import com.prg.controlplane.exception.ResourceNotFoundException;
import com.prg.controlplane.kafka.EventPublisher;
import com.prg.controlplane.kafka.event.CommandIssuedEvent;
import com.prg.controlplane.repository.DeviceCommandRepository;
import com.prg.controlplane.repository.DeviceRepository;
import com.prg.controlplane.security.DevicePrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeviceCommandService {

    private final DeviceCommandRepository deviceCommandRepository;
    private final DeviceRepository deviceRepository;
    private final EventPublisher eventPublisher;

    @Transactional
    public CommandResponse createCommand(UUID deviceId, CreateCommandRequest request,
                                          UUID tenantId, UUID createdBy) {
        Device device = deviceRepository.findByIdAndTenantId(deviceId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Device not found: " + deviceId, "DEVICE_NOT_FOUND"));

        if (!device.getIsActive()) {
            throw new IllegalStateException("Cannot send command to inactive device: " + deviceId);
        }

        DeviceCommand command = DeviceCommand.builder()
                .tenantId(tenantId)
                .deviceId(deviceId)
                .commandType(request.getCommandType())
                .payload(request.getPayload() != null ? request.getPayload() : Map.of())
                .status("pending")
                .createdBy(createdBy)
                .build();

        command = deviceCommandRepository.save(command);

        log.info("Command created: id={}, type={}, device_id={}, tenant_id={}",
                command.getId(), command.getCommandType(), deviceId, tenantId);

        // Dual-write to Kafka
        eventPublisher.publish("commands.issued",
            deviceId.toString(),
            CommandIssuedEvent.builder()
                .commandId(command.getId())
                .commandType(command.getCommandType())
                .tenantId(tenantId)
                .deviceId(deviceId)
                .payload(command.getPayload())
                .createdBy(createdBy)
                .createdAt(command.getCreatedTs())
                .expiresAt(command.getExpiresAt())
                .build());

        return toResponse(command);
    }

    @Transactional
    public void acknowledgeCommand(UUID commandId, AckCommandRequest request, DevicePrincipal principal) {
        DeviceCommand command = deviceCommandRepository.findByIdAndTenantId(commandId, principal.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Command not found: " + commandId, "COMMAND_NOT_FOUND"));

        // Verify device ownership: the principal's device must own this command
        if (principal.getDeviceId() != null && !principal.getDeviceId().equals(command.getDeviceId())) {
            log.warn("Command ack device mismatch: principal.deviceId={}, command.deviceId={}, commandId={}",
                    principal.getDeviceId(), command.getDeviceId(), commandId);
            throw new AccessDeniedException("Cannot acknowledge command for another device");
        }

        command.setStatus(request.getStatus());
        command.setAcknowledgedTs(Instant.now());
        if (request.getResult() != null) {
            command.setResult(request.getResult());
        }

        deviceCommandRepository.save(command);

        log.info("Command acknowledged: id={}, status={}, device_id={}",
                commandId, request.getStatus(), command.getDeviceId());
    }

    @Transactional(readOnly = true)
    public PageResponse<CommandResponse> getCommands(UUID deviceId, UUID tenantId,
                                                      String status, int page, int size) {
        // Verify device exists and belongs to tenant
        deviceRepository.findByIdAndTenantId(deviceId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Device not found: " + deviceId, "DEVICE_NOT_FOUND"));

        PageRequest pageRequest = PageRequest.of(page, Math.min(size, 100));

        Page<DeviceCommand> commandPage = deviceCommandRepository
                .findByDeviceIdAndTenantIdWithFilters(deviceId, tenantId, status, pageRequest);

        return PageResponse.<CommandResponse>builder()
                .content(commandPage.getContent().stream().map(this::toResponse).toList())
                .page(commandPage.getNumber())
                .size(commandPage.getSize())
                .totalElements(commandPage.getTotalElements())
                .totalPages(commandPage.getTotalPages())
                .build();
    }

    private CommandResponse toResponse(DeviceCommand cmd) {
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
