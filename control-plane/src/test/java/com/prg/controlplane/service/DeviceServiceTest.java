package com.prg.controlplane.service;

import com.prg.controlplane.dto.request.HeartbeatRequest;
import com.prg.controlplane.dto.response.DeviceDetailResponse;
import com.prg.controlplane.dto.response.DeviceResponse;
import com.prg.controlplane.dto.response.HeartbeatResponse;
import com.prg.controlplane.dto.response.PageResponse;
import com.prg.controlplane.entity.Device;
import com.prg.controlplane.entity.DeviceCommand;
import com.prg.controlplane.exception.AccessDeniedException;
import com.prg.controlplane.exception.ResourceNotFoundException;
import com.prg.controlplane.repository.DeviceCommandRepository;
import com.prg.controlplane.repository.DeviceRepository;
import com.prg.controlplane.repository.RecordingSessionRepository;
import com.prg.controlplane.security.DevicePrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeviceServiceTest {

    @Mock
    private DeviceRepository deviceRepository;

    @Mock
    private DeviceCommandRepository deviceCommandRepository;

    @Mock
    private RecordingSessionRepository recordingSessionRepository;

    @InjectMocks
    private DeviceService deviceService;

    private UUID tenantId;
    private UUID deviceId;
    private Device device;
    private DevicePrincipal principal;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        deviceId = UUID.randomUUID();

        ReflectionTestUtils.setField(deviceService, "defaultHeartbeatIntervalSec", 30);

        device = Device.builder()
                .id(deviceId)
                .tenantId(tenantId)
                .userId(UUID.randomUUID())
                .hostname("WORKSTATION-01")
                .osVersion("Windows 11 Pro")
                .agentVersion("1.0.0")
                .hardwareId("HW-ABC-123")
                .status("online")
                .lastHeartbeatTs(Instant.now().minusSeconds(10))
                .ipAddress("192.168.1.100")
                .settings(Map.of("capture_fps", 5, "quality", "medium"))
                .isActive(true)
                .createdTs(Instant.now().minusSeconds(3600))
                .updatedTs(Instant.now().minusSeconds(60))
                .build();

        principal = DevicePrincipal.builder()
                .userId(UUID.randomUUID())
                .tenantId(tenantId)
                .deviceId(deviceId)
                .roles(List.of("OPERATOR"))
                .permissions(List.of("DEVICES:READ", "DEVICES:UPDATE"))
                .scopes(List.of("tenant"))
                .build();
    }

    @Test
    @DisplayName("Get devices - filter by status returns matching devices")
    void testGetDevices_filterByStatus() {
        Device device2 = Device.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .hostname("WORKSTATION-02")
                .status("online")
                .isActive(true)
                .settings(Map.of())
                .createdTs(Instant.now())
                .updatedTs(Instant.now())
                .build();

        Page<Device> page = new PageImpl<>(List.of(device, device2), PageRequest.of(0, 20), 2);
        when(deviceRepository.findByTenantIdWithFilters(eq(tenantId), eq("online"), isNull(), eq(false), any(PageRequest.class)))
                .thenReturn(page);

        PageResponse<DeviceResponse> result = deviceService.getDevices(tenantId, "online", null, false, 0, 20);

        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getTotalElements()).isEqualTo(2);
        assertThat(result.getContent().get(0).getStatus()).isEqualTo("online");
        verify(deviceRepository).findByTenantIdWithFilters(eq(tenantId), eq("online"), isNull(), eq(false), any(PageRequest.class));
    }

    @Test
    @DisplayName("Get device - not found throws ResourceNotFoundException")
    void testGetDevice_notFound_throwsException() {
        UUID unknownId = UUID.randomUUID();
        when(deviceRepository.findByIdAndTenantId(unknownId, tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> deviceService.getDevice(unknownId, tenantId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Device not found");
    }

    @Test
    @DisplayName("Process heartbeat - updates device and delivers pending commands")
    void testProcessHeartbeat_updatesDeviceAndDeliverCommands() {
        HeartbeatRequest request = HeartbeatRequest.builder()
                .status("recording")
                .agentVersion("1.1.0")
                .metrics(Map.of("cpu_percent", 45.0, "memory_mb", 2048))
                .build();

        DeviceCommand pendingCommand = DeviceCommand.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .deviceId(deviceId)
                .commandType("START_RECORDING")
                .payload(Map.of())
                .status("pending")
                .createdTs(Instant.now().minusSeconds(30))
                .build();

        when(deviceRepository.findByIdAndTenantId(deviceId, tenantId)).thenReturn(Optional.of(device));
        when(deviceCommandRepository.findPendingCommandsByDeviceIdAndTenantId(eq(deviceId), eq(tenantId), any(Instant.class)))
                .thenReturn(List.of(pendingCommand));
        when(deviceRepository.save(any(Device.class))).thenAnswer(i -> i.getArgument(0));
        when(deviceCommandRepository.saveAll(anyList())).thenAnswer(i -> i.getArgument(0));

        HeartbeatResponse response = deviceService.processHeartbeat(deviceId, request, "10.0.0.1", principal);

        assertThat(response).isNotNull();
        assertThat(response.getServerTs()).isNotNull();
        assertThat(response.getNextHeartbeatSec()).isEqualTo(30);
        assertThat(response.getPendingCommands()).hasSize(1);
        assertThat(response.getPendingCommands().get(0).getCommandType()).isEqualTo("START_RECORDING");

        // Verify device was updated
        verify(deviceRepository).save(argThat(d -> {
            assertThat(d.getStatus()).isEqualTo("recording");
            assertThat(d.getAgentVersion()).isEqualTo("1.1.0");
            assertThat(d.getIpAddress()).isEqualTo("10.0.0.1");
            assertThat(d.getLastHeartbeatTs()).isNotNull();
            assertThat(d.getLastRecordingTs()).isNotNull();
            return true;
        }));

        // Verify commands were marked as delivered
        verify(deviceCommandRepository).saveAll(argThat(commands -> {
            List<DeviceCommand> cmdList = (List<DeviceCommand>) commands;
            assertThat(cmdList).hasSize(1);
            assertThat(cmdList.get(0).getStatus()).isEqualTo("delivered");
            assertThat(cmdList.get(0).getDeliveredTs()).isNotNull();
            return true;
        }));
    }

    @Test
    @DisplayName("Process heartbeat - wrong device throws AccessDeniedException")
    void testProcessHeartbeat_wrongDevice_throwsAccessDenied() {
        UUID otherDeviceId = UUID.randomUUID();

        Device otherDevice = Device.builder()
                .id(otherDeviceId)
                .tenantId(tenantId)
                .hostname("OTHER-PC")
                .status("online")
                .isActive(true)
                .settings(Map.of())
                .createdTs(Instant.now())
                .updatedTs(Instant.now())
                .build();

        // Principal has deviceId set to a different device, and does NOT have DEVICES:UPDATE permission
        DevicePrincipal restrictedPrincipal = DevicePrincipal.builder()
                .userId(UUID.randomUUID())
                .tenantId(tenantId)
                .deviceId(deviceId) // principal's device
                .roles(List.of("DEVICE_AGENT"))
                .permissions(List.of()) // no DEVICES:UPDATE
                .scopes(List.of("device"))
                .build();

        HeartbeatRequest request = HeartbeatRequest.builder()
                .status("online")
                .build();

        when(deviceRepository.findByIdAndTenantId(otherDeviceId, tenantId)).thenReturn(Optional.of(otherDevice));

        assertThatThrownBy(() -> deviceService.processHeartbeat(otherDeviceId, request, "10.0.0.1", restrictedPrincipal))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Device ID mismatch");
    }

    @Test
    @DisplayName("Soft delete device - sets offline, inactive, and is_deleted=true, interrupts sessions, expires commands")
    void testSoftDeleteDevice_setsDeletedOfflineInactive() {
        UUID userId = UUID.randomUUID();
        when(deviceRepository.findByIdAndTenantId(deviceId, tenantId)).thenReturn(Optional.of(device));
        when(deviceRepository.save(any(Device.class))).thenAnswer(i -> i.getArgument(0));
        when(recordingSessionRepository.interruptActiveSessionsByDeviceId(eq(deviceId), eq(tenantId), any(Instant.class))).thenReturn(0);
        when(deviceCommandRepository.expirePendingCommandsByDeviceId(eq(deviceId), eq(tenantId))).thenReturn(0);

        deviceService.softDeleteDevice(deviceId, tenantId, userId, "10.0.0.1", "TestAgent");

        verify(recordingSessionRepository).interruptActiveSessionsByDeviceId(eq(deviceId), eq(tenantId), any(Instant.class));
        verify(deviceCommandRepository).expirePendingCommandsByDeviceId(deviceId, tenantId);
        verify(deviceRepository).save(argThat(d -> {
            assertThat(d.getIsActive()).isFalse();
            assertThat(d.getStatus()).isEqualTo("offline");
            assertThat(d.getIsDeleted()).isTrue();
            assertThat(d.getDeletedTs()).isNotNull();
            return true;
        }));
    }
}
