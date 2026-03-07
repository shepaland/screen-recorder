package com.prg.ingest.service;

import com.prg.ingest.dto.request.FocusIntervalItem;
import com.prg.ingest.dto.request.SubmitFocusIntervalsRequest;
import com.prg.ingest.dto.response.FocusIntervalsResponse;
import com.prg.ingest.entity.DeviceUserSession;
import com.prg.ingest.exception.AccessDeniedException;
import com.prg.ingest.repository.AppFocusIntervalRepository;
import com.prg.ingest.repository.DeviceUserSessionRepository;
import com.prg.ingest.security.DevicePrincipal;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FocusIntervalServiceTest {

    @Mock
    private AppFocusIntervalRepository focusRepo;

    @Mock
    private DeviceUserSessionRepository sessionRepo;

    @Mock
    private EntityManager entityManager;

    @InjectMocks
    private FocusIntervalService service;

    private UUID tenantId;
    private UUID deviceId;
    private DevicePrincipal principal;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        deviceId = UUID.randomUUID();
        principal = DevicePrincipal.builder()
                .userId(UUID.randomUUID())
                .tenantId(tenantId)
                .deviceId(deviceId)
                .roles(List.of())
                .permissions(List.of())
                .build();
    }

    @Test
    void submitIntervals_success() {
        // Given
        UUID intervalId = UUID.randomUUID();
        SubmitFocusIntervalsRequest request = SubmitFocusIntervalsRequest.builder()
                .deviceId(deviceId)
                .username("DOMAIN\\testuser")
                .intervals(List.of(
                        FocusIntervalItem.builder()
                                .id(intervalId)
                                .processName("chrome.exe")
                                .windowTitle("Google - Google Chrome")
                                .isBrowser(true)
                                .browserName("Chrome")
                                .domain("google.com")
                                .startedAt(Instant.now().minusSeconds(60))
                                .endedAt(Instant.now())
                                .durationMs(60000)
                                .build()
                ))
                .build();

        when(focusRepo.findExistingIds(anySet())).thenReturn(Set.of());
        when(sessionRepo.findByTenantIdAndDeviceIdAndUsernameAndIsActiveTrue(
                eq(tenantId), eq(deviceId), eq("DOMAIN\\testuser")))
                .thenReturn(Optional.empty());
        when(sessionRepo.save(any(DeviceUserSession.class))).thenAnswer(inv -> inv.getArgument(0));

        // When
        FocusIntervalsResponse response = service.submitIntervals(request, principal, UUID.randomUUID());

        // Then
        assertEquals(1, response.getAccepted());
        assertEquals(0, response.getDuplicates());
        assertNotNull(response.getCorrelationId());
        verify(entityManager, times(1)).persist(any());
        verify(entityManager, times(1)).flush();
        verify(sessionRepo, times(1)).save(any(DeviceUserSession.class));
    }

    @Test
    void submitIntervals_deviceMismatch_throws403() {
        // Given
        UUID wrongDeviceId = UUID.randomUUID();
        SubmitFocusIntervalsRequest request = SubmitFocusIntervalsRequest.builder()
                .deviceId(wrongDeviceId) // Different from principal's device
                .username("testuser")
                .intervals(List.of(
                        FocusIntervalItem.builder()
                                .id(UUID.randomUUID())
                                .processName("app.exe")
                                .isBrowser(false)
                                .startedAt(Instant.now().minusSeconds(60))
                                .durationMs(60000)
                                .build()
                ))
                .build();

        // When & Then
        AccessDeniedException ex = assertThrows(
                AccessDeniedException.class,
                () -> service.submitIntervals(request, principal, UUID.randomUUID()));
        assertEquals("DEVICE_MISMATCH", ex.getCode());
    }

    @Test
    void submitIntervals_deviceIdNullInJwt_throws403() {
        // Given
        DevicePrincipal noDevicePrincipal = DevicePrincipal.builder()
                .userId(UUID.randomUUID())
                .tenantId(tenantId)
                .deviceId(null) // No device_id
                .build();

        SubmitFocusIntervalsRequest request = SubmitFocusIntervalsRequest.builder()
                .deviceId(deviceId)
                .username("testuser")
                .intervals(List.of(
                        FocusIntervalItem.builder()
                                .id(UUID.randomUUID())
                                .processName("app.exe")
                                .isBrowser(false)
                                .startedAt(Instant.now().minusSeconds(60))
                                .durationMs(60000)
                                .build()
                ))
                .build();

        // When & Then
        AccessDeniedException ex = assertThrows(
                AccessDeniedException.class,
                () -> service.submitIntervals(request, noDevicePrincipal, UUID.randomUUID()));
        assertEquals("DEVICE_ID_REQUIRED", ex.getCode());
    }

    @Test
    void submitIntervals_duplicates_deduped() {
        // Given
        UUID intervalId = UUID.randomUUID();
        SubmitFocusIntervalsRequest request = SubmitFocusIntervalsRequest.builder()
                .deviceId(deviceId)
                .username("testuser")
                .intervals(List.of(
                        FocusIntervalItem.builder()
                                .id(intervalId)
                                .processName("app.exe")
                                .isBrowser(false)
                                .startedAt(Instant.now().minusSeconds(60))
                                .durationMs(60000)
                                .build()
                ))
                .build();

        // Simulate existing ID (duplicate)
        when(focusRepo.findExistingIds(anySet())).thenReturn(Set.of(intervalId));
        when(sessionRepo.findByTenantIdAndDeviceIdAndUsernameAndIsActiveTrue(
                any(), any(), any())).thenReturn(Optional.empty());
        when(sessionRepo.save(any(DeviceUserSession.class))).thenAnswer(inv -> inv.getArgument(0));

        // When
        FocusIntervalsResponse response = service.submitIntervals(request, principal, UUID.randomUUID());

        // Then
        assertEquals(0, response.getAccepted());
        assertEquals(1, response.getDuplicates());
        verify(entityManager, never()).persist(any());
    }
}
