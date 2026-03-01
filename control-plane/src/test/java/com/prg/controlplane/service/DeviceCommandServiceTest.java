package com.prg.controlplane.service;

import com.prg.controlplane.dto.request.AckCommandRequest;
import com.prg.controlplane.dto.request.CreateCommandRequest;
import com.prg.controlplane.dto.response.CommandResponse;
import com.prg.controlplane.entity.Device;
import com.prg.controlplane.entity.DeviceCommand;
import com.prg.controlplane.exception.AccessDeniedException;
import com.prg.controlplane.exception.ResourceNotFoundException;
import com.prg.controlplane.repository.DeviceCommandRepository;
import com.prg.controlplane.repository.DeviceRepository;
import com.prg.controlplane.security.DevicePrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeviceCommandServiceTest {

    @Mock
    private DeviceCommandRepository deviceCommandRepository;

    @Mock
    private DeviceRepository deviceRepository;

    @InjectMocks
    private DeviceCommandService deviceCommandService;

    private UUID tenantId;
    private UUID deviceId;
    private UUID userId;
    private Device device;
    private DevicePrincipal principal;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        deviceId = UUID.randomUUID();
        userId = UUID.randomUUID();

        device = Device.builder()
                .id(deviceId)
                .tenantId(tenantId)
                .hostname("WORKSTATION-01")
                .status("online")
                .isActive(true)
                .settings(Map.of())
                .createdTs(Instant.now())
                .updatedTs(Instant.now())
                .build();

        principal = DevicePrincipal.builder()
                .userId(userId)
                .tenantId(tenantId)
                .deviceId(deviceId)
                .roles(List.of("OPERATOR"))
                .permissions(List.of("DEVICES:READ", "DEVICES:COMMAND"))
                .scopes(List.of("tenant"))
                .build();
    }

    @Test
    @DisplayName("Create command - success creates pending command")
    void testCreateCommand_success() {
        CreateCommandRequest request = CreateCommandRequest.builder()
                .commandType("START_RECORDING")
                .payload(Map.of("source", "schedule"))
                .build();

        when(deviceRepository.findByIdAndTenantId(deviceId, tenantId)).thenReturn(Optional.of(device));
        when(deviceCommandRepository.save(any(DeviceCommand.class))).thenAnswer(invocation -> {
            DeviceCommand cmd = invocation.getArgument(0);
            cmd.setId(UUID.randomUUID());
            cmd.setCreatedTs(Instant.now());
            cmd.setExpiresAt(Instant.now().plusSeconds(86400));
            return cmd;
        });

        CommandResponse response = deviceCommandService.createCommand(deviceId, request, tenantId, userId);

        assertThat(response).isNotNull();
        assertThat(response.getId()).isNotNull();
        assertThat(response.getDeviceId()).isEqualTo(deviceId);
        assertThat(response.getCommandType()).isEqualTo("START_RECORDING");
        assertThat(response.getStatus()).isEqualTo("pending");
        assertThat(response.getCreatedBy()).isEqualTo(userId);

        verify(deviceCommandRepository).save(argThat(cmd -> {
            assertThat(cmd.getTenantId()).isEqualTo(tenantId);
            assertThat(cmd.getDeviceId()).isEqualTo(deviceId);
            assertThat(cmd.getCommandType()).isEqualTo("START_RECORDING");
            assertThat(cmd.getStatus()).isEqualTo("pending");
            return true;
        }));
    }

    @Test
    @DisplayName("Create command - device inactive throws IllegalStateException")
    void testCreateCommand_deviceInactive_throwsException() {
        device.setIsActive(false);

        CreateCommandRequest request = CreateCommandRequest.builder()
                .commandType("START_RECORDING")
                .build();

        when(deviceRepository.findByIdAndTenantId(deviceId, tenantId)).thenReturn(Optional.of(device));

        assertThatThrownBy(() -> deviceCommandService.createCommand(deviceId, request, tenantId, userId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inactive device");
    }

    @Test
    @DisplayName("Acknowledge command - success updates status and result")
    void testAcknowledgeCommand_success() {
        UUID commandId = UUID.randomUUID();

        DeviceCommand command = DeviceCommand.builder()
                .id(commandId)
                .tenantId(tenantId)
                .deviceId(deviceId)
                .commandType("START_RECORDING")
                .payload(Map.of())
                .status("delivered")
                .createdTs(Instant.now().minusSeconds(60))
                .deliveredTs(Instant.now().minusSeconds(30))
                .build();

        AckCommandRequest request = AckCommandRequest.builder()
                .status("acknowledged")
                .result(Map.of("session_id", UUID.randomUUID().toString()))
                .build();

        when(deviceCommandRepository.findByIdAndTenantId(commandId, tenantId)).thenReturn(Optional.of(command));
        when(deviceCommandRepository.save(any(DeviceCommand.class))).thenAnswer(i -> i.getArgument(0));

        deviceCommandService.acknowledgeCommand(commandId, request, principal);

        verify(deviceCommandRepository).save(argThat(cmd -> {
            assertThat(cmd.getStatus()).isEqualTo("acknowledged");
            assertThat(cmd.getAcknowledgedTs()).isNotNull();
            assertThat(cmd.getResult()).containsKey("session_id");
            return true;
        }));
    }

    @Test
    @DisplayName("Acknowledge command - wrong device throws AccessDeniedException")
    void testAcknowledgeCommand_wrongDevice_throwsAccessDenied() {
        UUID commandId = UUID.randomUUID();
        UUID otherDeviceId = UUID.randomUUID();

        DeviceCommand command = DeviceCommand.builder()
                .id(commandId)
                .tenantId(tenantId)
                .deviceId(otherDeviceId) // belongs to a different device
                .commandType("STOP_RECORDING")
                .payload(Map.of())
                .status("delivered")
                .createdTs(Instant.now())
                .build();

        AckCommandRequest request = AckCommandRequest.builder()
                .status("acknowledged")
                .build();

        when(deviceCommandRepository.findByIdAndTenantId(commandId, tenantId)).thenReturn(Optional.of(command));

        assertThatThrownBy(() -> deviceCommandService.acknowledgeCommand(commandId, request, principal))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("another device");
    }
}
