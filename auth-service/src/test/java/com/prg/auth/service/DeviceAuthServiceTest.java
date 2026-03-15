package com.prg.auth.service;

import com.prg.auth.config.JwtConfig;
import com.prg.auth.dto.request.DeviceLoginRequest;
import com.prg.auth.dto.request.DeviceRefreshRequest;
import com.prg.auth.dto.response.DeviceLoginResponse;
import com.prg.auth.dto.response.DeviceRefreshResponse;
import com.prg.auth.entity.*;
import com.prg.auth.exception.*;
import com.prg.auth.repository.*;
import com.prg.auth.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeviceAuthServiceTest {

    @Mock private DeviceRegistrationTokenRepository registrationTokenRepository;
    @Mock private DeviceRepository deviceRepository;
    @Mock private UserRepository userRepository;
    @Mock private TenantRepository tenantRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private JwtConfig jwtConfig;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private AuditService auditService;

    @InjectMocks
    private DeviceAuthService deviceAuthService;

    private Tenant tenant;
    private User user;
    private Permission permission;
    private Role managerRole;
    private DeviceRegistrationToken regToken;
    private String rawRegToken;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(deviceAuthService, "maxLoginAttempts", 5);
        ReflectionTestUtils.setField(deviceAuthService, "loginAttemptWindow", 900L);
        ReflectionTestUtils.setField(deviceAuthService, "heartbeatIntervalSec", 30);
        ReflectionTestUtils.setField(deviceAuthService, "segmentDurationSec", 10);
        ReflectionTestUtils.setField(deviceAuthService, "captureFps", 5);
        ReflectionTestUtils.setField(deviceAuthService, "quality", "medium");
        ReflectionTestUtils.setField(deviceAuthService, "ingestBaseUrl", "https://ingest.example.com");
        ReflectionTestUtils.setField(deviceAuthService, "controlPlaneBaseUrl", "https://cp.example.com");

        tenant = Tenant.builder()
                .id(UUID.randomUUID())
                .name("Test Tenant")
                .slug("test-tenant")
                .isActive(true)
                .settings(Map.of())
                .createdTs(Instant.now())
                .updatedTs(Instant.now())
                .build();

        permission = Permission.builder()
                .id(UUID.randomUUID())
                .code("DEVICE_TOKENS:CREATE")
                .name("Create Device Tokens")
                .resource("DEVICE_TOKENS")
                .action("CREATE")
                .build();

        managerRole = Role.builder()
                .id(UUID.randomUUID())
                .tenant(tenant)
                .code("MANAGER")
                .name("Manager")
                .isSystem(true)
                .permissions(Set.of(permission))
                .createdTs(Instant.now())
                .updatedTs(Instant.now())
                .build();

        user = User.builder()
                .id(UUID.randomUUID())
                .tenant(tenant)
                .username("manager")
                .email("manager@test.com")
                .passwordHash("$2a$12$hashedpassword")
                .firstName("Test")
                .lastName("Manager")
                .isActive(true)
                .roles(Set.of(managerRole))
                .createdTs(Instant.now())
                .updatedTs(Instant.now())
                .build();

        rawRegToken = "drt_abc123def456";
        regToken = DeviceRegistrationToken.builder()
                .id(UUID.randomUUID())
                .tenant(tenant)
                .tokenHash(AuthService.sha256(rawRegToken))
                .name("Office Token")
                .maxUses(100)
                .currentUses(5)
                .isActive(true)
                .createdBy(user)
                .createdTs(Instant.now())
                .updatedTs(Instant.now())
                .build();
    }

    private DeviceLoginRequest buildLoginRequest() {
        return DeviceLoginRequest.builder()
                .username("manager")
                .password("Password123")
                .registrationToken(rawRegToken)
                .deviceInfo(DeviceLoginRequest.DeviceInfo.builder()
                        .hostname("WORKSTATION-01")
                        .osVersion("Windows 11 23H2")
                        .agentVersion("1.0.0")
                        .hardwareId("MB-ABC123-CPU-DEF456-DISK-GHI789")
                        .build())
                .build();
    }

    @Test
    @DisplayName("Device login success - new device registration")
    void deviceLoginSuccessNewDevice() {
        DeviceLoginRequest request = buildLoginRequest();
        String tokenHash = AuthService.sha256(rawRegToken);

        when(registrationTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(regToken));
        when(userRepository.findByTenantIdAndUsername(tenant.getId(), "manager")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Password123", user.getPasswordHash())).thenReturn(true);
        when(deviceRepository.findByTenantIdAndHardwareId(tenant.getId(), "MB-ABC123-CPU-DEF456-DISK-GHI789"))
                .thenReturn(Optional.empty());
        when(deviceRepository.save(any(Device.class))).thenAnswer(invocation -> {
            Device d = invocation.getArgument(0);
            d.setId(UUID.randomUUID());
            return d;
        });
        when(jwtTokenProvider.generateDeviceAccessToken(any(), any(), anyString(), anyString(),
                anyList(), anyList(), anyList(), any(), any()))
                .thenReturn("mock-device-access-token");
        when(jwtTokenProvider.getAccessTokenTtl()).thenReturn(900L);
        when(jwtConfig.getRefreshTokenTtl()).thenReturn(2592000L);
        when(refreshTokenRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        DeviceLoginResponse response = deviceAuthService.deviceLogin(request, "192.168.1.100", "PRG-Agent/1.0");

        assertThat(response).isNotNull();
        assertThat(response.getAccessToken()).isEqualTo("mock-device-access-token");
        assertThat(response.getRefreshToken()).isNotBlank();
        assertThat(response.getTokenType()).isEqualTo("Bearer");
        assertThat(response.getExpiresIn()).isEqualTo(900L);
        assertThat(response.getDeviceId()).isNotNull();
        assertThat(response.getDeviceStatus()).isEqualTo("online");
        assertThat(response.getUser()).isNotNull();
        assertThat(response.getUser().getUsername()).isEqualTo("manager");
        assertThat(response.getServerConfig()).isNotNull();
        assertThat(response.getServerConfig().getHeartbeatIntervalSec()).isEqualTo(30);
        assertThat(response.getServerConfig().getSegmentDurationSec()).isEqualTo(10);
        assertThat(response.getServerConfig().getCaptureFps()).isEqualTo(5);
        assertThat(response.getServerConfig().getQuality()).isEqualTo("medium");
        assertThat(response.getServerConfig().getIngestBaseUrl()).isEqualTo("https://ingest.example.com");
        assertThat(response.getServerConfig().getControlPlaneBaseUrl()).isEqualTo("https://cp.example.com");

        verify(registrationTokenRepository).incrementCurrentUses(regToken.getId());
        verify(deviceRepository).save(any(Device.class));
        verify(refreshTokenRepository).save(any(RefreshToken.class));
        verify(userRepository).updateLastLoginTs(eq(user.getId()), any(Instant.class));
        verify(auditService).logAction(eq(tenant.getId()), eq(user.getId()),
                eq("DEVICE_REGISTERED"), eq("DEVICES"), any(UUID.class), anyMap(),
                eq("192.168.1.100"), eq("PRG-Agent/1.0"), any());
    }

    @Test
    @DisplayName("Device login success - existing active device re-login")
    void deviceLoginSuccessExistingDevice() {
        DeviceLoginRequest request = buildLoginRequest();
        String tokenHash = AuthService.sha256(rawRegToken);

        Device existingDevice = Device.builder()
                .id(UUID.randomUUID())
                .tenant(tenant)
                .user(user)
                .registrationToken(regToken)
                .hostname("OLD-HOSTNAME")
                .osVersion("Windows 10")
                .agentVersion("0.9.0")
                .hardwareId("MB-ABC123-CPU-DEF456-DISK-GHI789")
                .status("offline")
                .isActive(true)
                .settings(Map.of())
                .createdTs(Instant.now())
                .updatedTs(Instant.now())
                .build();

        when(registrationTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(regToken));
        when(userRepository.findByTenantIdAndUsername(tenant.getId(), "manager")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Password123", user.getPasswordHash())).thenReturn(true);
        when(deviceRepository.findByTenantIdAndHardwareId(tenant.getId(), "MB-ABC123-CPU-DEF456-DISK-GHI789"))
                .thenReturn(Optional.of(existingDevice));
        when(deviceRepository.save(any(Device.class))).thenAnswer(i -> i.getArgument(0));
        when(jwtTokenProvider.generateDeviceAccessToken(any(), any(), anyString(), anyString(),
                anyList(), anyList(), anyList(), any(), any()))
                .thenReturn("mock-device-access-token");
        when(jwtTokenProvider.getAccessTokenTtl()).thenReturn(900L);
        when(jwtConfig.getRefreshTokenTtl()).thenReturn(2592000L);
        when(refreshTokenRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        DeviceLoginResponse response = deviceAuthService.deviceLogin(request, "192.168.1.100", "PRG-Agent/1.0");

        assertThat(response).isNotNull();
        assertThat(response.getDeviceId()).isEqualTo(existingDevice.getId());

        // Verify device was updated with new info
        verify(deviceRepository).save(argThat(device ->
                device.getHostname().equals("WORKSTATION-01") &&
                device.getOsVersion().equals("Windows 11 23H2") &&
                device.getAgentVersion().equals("1.0.0") &&
                device.getStatus().equals("online")
        ));

        verify(auditService).logAction(eq(tenant.getId()), eq(user.getId()),
                eq("DEVICE_RELOGIN"), eq("DEVICES"), eq(existingDevice.getId()), anyMap(),
                anyString(), anyString(), any());
    }

    @Test
    @DisplayName("Device login failure - invalid registration token")
    void deviceLoginInvalidRegToken() {
        DeviceLoginRequest request = buildLoginRequest();
        request.setRegistrationToken("invalid_token");

        when(registrationTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> deviceAuthService.deviceLogin(request, "127.0.0.1", "Agent"))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessageContaining("Invalid registration token");
    }

    @Test
    @DisplayName("Device login failure - deactivated registration token")
    void deviceLoginDeactivatedRegToken() {
        DeviceLoginRequest request = buildLoginRequest();
        regToken.setIsActive(false);
        String tokenHash = AuthService.sha256(rawRegToken);

        when(registrationTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(regToken));

        assertThatThrownBy(() -> deviceAuthService.deviceLogin(request, "127.0.0.1", "Agent"))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessageContaining("deactivated");
    }

    @Test
    @DisplayName("Device login failure - expired registration token")
    void deviceLoginExpiredRegToken() {
        DeviceLoginRequest request = buildLoginRequest();
        regToken.setExpiresAt(Instant.now().minusSeconds(3600));
        String tokenHash = AuthService.sha256(rawRegToken);

        when(registrationTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(regToken));

        assertThatThrownBy(() -> deviceAuthService.deviceLogin(request, "127.0.0.1", "Agent"))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessageContaining("expired");
    }

    @Test
    @DisplayName("Device login failure - registration token usage limit exceeded")
    void deviceLoginUsageLimitExceeded() {
        DeviceLoginRequest request = buildLoginRequest();
        regToken.setMaxUses(5);
        regToken.setCurrentUses(5);
        String tokenHash = AuthService.sha256(rawRegToken);

        when(registrationTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(regToken));

        assertThatThrownBy(() -> deviceAuthService.deviceLogin(request, "127.0.0.1", "Agent"))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessageContaining("limit exceeded");
    }

    @Test
    @DisplayName("Device login failure - invalid credentials")
    void deviceLoginInvalidCredentials() {
        DeviceLoginRequest request = buildLoginRequest();
        String tokenHash = AuthService.sha256(rawRegToken);

        when(registrationTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(regToken));
        when(userRepository.findByTenantIdAndUsername(tenant.getId(), "manager")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Password123", user.getPasswordHash())).thenReturn(false);

        assertThatThrownBy(() -> deviceAuthService.deviceLogin(request, "127.0.0.1", "Agent"))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("Invalid username or password");
    }

    @Test
    @DisplayName("Device login failure - user not found")
    void deviceLoginUserNotFound() {
        DeviceLoginRequest request = buildLoginRequest();
        String tokenHash = AuthService.sha256(rawRegToken);

        when(registrationTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(regToken));
        when(userRepository.findByTenantIdAndUsername(tenant.getId(), "manager")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> deviceAuthService.deviceLogin(request, "127.0.0.1", "Agent"))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("Invalid username or password");
    }

    @Test
    @DisplayName("Device login failure - insufficient role (OPERATOR)")
    void deviceLoginInsufficientRole() {
        DeviceLoginRequest request = buildLoginRequest();
        String tokenHash = AuthService.sha256(rawRegToken);

        Role operatorRole = Role.builder()
                .id(UUID.randomUUID())
                .tenant(tenant)
                .code("OPERATOR")
                .name("Operator")
                .isSystem(true)
                .permissions(Set.of())
                .createdTs(Instant.now())
                .updatedTs(Instant.now())
                .build();
        user.setRoles(Set.of(operatorRole));

        when(registrationTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(regToken));
        when(userRepository.findByTenantIdAndUsername(tenant.getId(), "manager")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Password123", user.getPasswordHash())).thenReturn(true);

        assertThatThrownBy(() -> deviceAuthService.deviceLogin(request, "127.0.0.1", "Agent"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Insufficient role");
    }

    @Test
    @DisplayName("Device login failure - deactivated device")
    void deviceLoginDeactivatedDevice() {
        DeviceLoginRequest request = buildLoginRequest();
        String tokenHash = AuthService.sha256(rawRegToken);

        Device deactivatedDevice = Device.builder()
                .id(UUID.randomUUID())
                .tenant(tenant)
                .user(user)
                .hostname("OLD")
                .hardwareId("MB-ABC123-CPU-DEF456-DISK-GHI789")
                .status("offline")
                .isActive(false)
                .settings(Map.of())
                .createdTs(Instant.now())
                .updatedTs(Instant.now())
                .build();

        when(registrationTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(regToken));
        when(userRepository.findByTenantIdAndUsername(tenant.getId(), "manager")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Password123", user.getPasswordHash())).thenReturn(true);
        when(deviceRepository.findByTenantIdAndHardwareId(tenant.getId(), "MB-ABC123-CPU-DEF456-DISK-GHI789"))
                .thenReturn(Optional.of(deactivatedDevice));

        assertThatThrownBy(() -> deviceAuthService.deviceLogin(request, "127.0.0.1", "Agent"))
                .isInstanceOf(DeviceDeactivatedException.class)
                .hasMessageContaining("deactivated");
    }

    @Test
    @DisplayName("Device refresh - success with token rotation")
    void deviceRefreshSuccess() {
        UUID deviceId = UUID.randomUUID();
        Device device = Device.builder()
                .id(deviceId)
                .tenant(tenant)
                .user(user)
                .hostname("WORKSTATION-01")
                .hardwareId("HW-123")
                .status("online")
                .isActive(true)
                .settings(Map.of())
                .createdTs(Instant.now())
                .updatedTs(Instant.now())
                .build();

        String rawToken = UUID.randomUUID().toString();
        String tokenHash = AuthService.sha256(rawToken);

        RefreshToken storedToken = RefreshToken.builder()
                .id(UUID.randomUUID())
                .user(user)
                .tenantId(tenant.getId())
                .tokenHash(tokenHash)
                .deviceInfo("device:" + deviceId)
                .expiresAt(Instant.now().plusSeconds(86400))
                .isRevoked(false)
                .build();

        DeviceRefreshRequest request = DeviceRefreshRequest.builder()
                .refreshToken(rawToken)
                .deviceId(deviceId)
                .build();

        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(device));
        when(refreshTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(storedToken));
        when(tenantRepository.findById(tenant.getId())).thenReturn(Optional.of(tenant));
        when(jwtTokenProvider.generateDeviceAccessToken(any(), any(), anyString(), anyString(),
                anyList(), anyList(), anyList(), eq(deviceId), any()))
                .thenReturn("new-device-access-token");
        when(jwtTokenProvider.getAccessTokenTtl()).thenReturn(900L);
        when(jwtConfig.getRefreshTokenTtl()).thenReturn(2592000L);
        when(refreshTokenRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        DeviceRefreshResponse response = deviceAuthService.deviceRefresh(request, "192.168.1.100", "PRG-Agent/1.0");

        assertThat(response).isNotNull();
        assertThat(response.getAccessToken()).isEqualTo("new-device-access-token");
        assertThat(response.getRefreshToken()).isNotBlank();
        assertThat(response.getRefreshToken()).isNotEqualTo(rawToken);
        assertThat(response.getTokenType()).isEqualTo("Bearer");
        assertThat(response.getExpiresIn()).isEqualTo(900L);

        verify(refreshTokenRepository).revokeById(storedToken.getId());
        verify(refreshTokenRepository).save(any(RefreshToken.class));
    }

    @Test
    @DisplayName("Device refresh failure - deactivated device")
    void deviceRefreshDeactivatedDevice() {
        UUID deviceId = UUID.randomUUID();
        Device device = Device.builder()
                .id(deviceId)
                .tenant(tenant)
                .user(user)
                .hostname("WORKSTATION-01")
                .status("offline")
                .isActive(false)
                .settings(Map.of())
                .createdTs(Instant.now())
                .updatedTs(Instant.now())
                .build();

        DeviceRefreshRequest request = DeviceRefreshRequest.builder()
                .refreshToken("some-token")
                .deviceId(deviceId)
                .build();

        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(device));

        assertThatThrownBy(() -> deviceAuthService.deviceRefresh(request, "127.0.0.1", "Agent"))
                .isInstanceOf(DeviceDeactivatedException.class);
    }

    @Test
    @DisplayName("Device refresh failure - device not found")
    void deviceRefreshDeviceNotFound() {
        UUID deviceId = UUID.randomUUID();
        DeviceRefreshRequest request = DeviceRefreshRequest.builder()
                .refreshToken("some-token")
                .deviceId(deviceId)
                .build();

        when(deviceRepository.findById(deviceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> deviceAuthService.deviceRefresh(request, "127.0.0.1", "Agent"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Device not found");
    }
}
