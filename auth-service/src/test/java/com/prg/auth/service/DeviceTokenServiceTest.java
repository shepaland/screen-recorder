package com.prg.auth.service;

import com.prg.auth.dto.request.CreateDeviceTokenRequest;
import com.prg.auth.dto.response.DeviceTokenResponse;
import com.prg.auth.dto.response.PageResponse;
import com.prg.auth.entity.DeviceRegistrationToken;
import com.prg.auth.entity.Tenant;
import com.prg.auth.entity.User;
import com.prg.auth.exception.ResourceNotFoundException;
import com.prg.auth.repository.DeviceRegistrationTokenRepository;
import com.prg.auth.repository.TenantRepository;
import com.prg.auth.repository.UserRepository;
import com.prg.auth.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeviceTokenServiceTest {

    @Mock private DeviceRegistrationTokenRepository tokenRepository;
    @Mock private TenantRepository tenantRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;

    @InjectMocks
    private DeviceTokenService deviceTokenService;

    private Tenant tenant;
    private User adminUser;
    private UserPrincipal principal;

    @BeforeEach
    void setUp() {
        tenant = Tenant.builder()
                .id(UUID.randomUUID())
                .name("Test Tenant")
                .slug("test-tenant")
                .isActive(true)
                .settings(Map.of())
                .createdTs(Instant.now())
                .updatedTs(Instant.now())
                .build();

        adminUser = User.builder()
                .id(UUID.randomUUID())
                .tenant(tenant)
                .username("admin")
                .email("admin@test.com")
                .passwordHash("$2a$12$hash")
                .isActive(true)
                .roles(Set.of())
                .createdTs(Instant.now())
                .updatedTs(Instant.now())
                .build();

        principal = UserPrincipal.builder()
                .userId(adminUser.getId())
                .tenantId(tenant.getId())
                .username("admin")
                .email("admin@test.com")
                .roles(List.of("TENANT_ADMIN"))
                .permissions(List.of("DEVICE_TOKENS:CREATE", "DEVICE_TOKENS:READ", "DEVICE_TOKENS:DELETE"))
                .scopes(List.of("tenant"))
                .build();
    }

    @Test
    @DisplayName("Create token - returns response with raw token")
    void createTokenSuccess() {
        CreateDeviceTokenRequest request = CreateDeviceTokenRequest.builder()
                .name("Office Token")
                .maxUses(50)
                .build();

        when(tenantRepository.findById(principal.getTenantId())).thenReturn(Optional.of(tenant));
        when(userRepository.findById(principal.getUserId())).thenReturn(Optional.of(adminUser));
        when(tokenRepository.save(any(DeviceRegistrationToken.class)))
                .thenAnswer(invocation -> {
                    DeviceRegistrationToken t = invocation.getArgument(0);
                    t.setId(UUID.randomUUID());
                    t.setCreatedTs(Instant.now());
                    return t;
                });

        DeviceTokenResponse response = deviceTokenService.createToken(request, principal, "127.0.0.1", "Test-Agent");

        assertThat(response).isNotNull();
        assertThat(response.getId()).isNotNull();
        assertThat(response.getToken()).startsWith("drt_");
        assertThat(response.getToken()).hasSize(36); // "drt_" + 32 hex chars
        assertThat(response.getName()).isEqualTo("Office Token");
        assertThat(response.getMaxUses()).isEqualTo(50);
        assertThat(response.getCurrentUses()).isEqualTo(0);
        assertThat(response.getIsActive()).isTrue();

        ArgumentCaptor<DeviceRegistrationToken> tokenCaptor = ArgumentCaptor.forClass(DeviceRegistrationToken.class);
        verify(tokenRepository).save(tokenCaptor.capture());
        DeviceRegistrationToken savedToken = tokenCaptor.getValue();
        assertThat(savedToken.getTokenHash()).hasSize(64); // SHA-256 hex
        assertThat(savedToken.getName()).isEqualTo("Office Token");
        assertThat(savedToken.getMaxUses()).isEqualTo(50);

        verify(auditService).logAction(eq(principal.getTenantId()), eq(principal.getUserId()),
                eq("DEVICE_TOKEN_CREATED"), eq("DEVICE_TOKENS"), any(UUID.class), anyMap(),
                eq("127.0.0.1"), eq("Test-Agent"), any());
    }

    @Test
    @DisplayName("Create token - unlimited uses when maxUses is null")
    void createTokenUnlimitedUses() {
        CreateDeviceTokenRequest request = CreateDeviceTokenRequest.builder()
                .name("Unlimited Token")
                .build();

        when(tenantRepository.findById(principal.getTenantId())).thenReturn(Optional.of(tenant));
        when(userRepository.findById(principal.getUserId())).thenReturn(Optional.of(adminUser));
        when(tokenRepository.save(any(DeviceRegistrationToken.class)))
                .thenAnswer(invocation -> {
                    DeviceRegistrationToken t = invocation.getArgument(0);
                    t.setId(UUID.randomUUID());
                    t.setCreatedTs(Instant.now());
                    return t;
                });

        DeviceTokenResponse response = deviceTokenService.createToken(request, principal, "127.0.0.1", "Test-Agent");

        assertThat(response.getMaxUses()).isNull();
        assertThat(response.getToken()).startsWith("drt_");
    }

    @Test
    @DisplayName("Get tokens - returns paginated list without raw tokens")
    void getTokensSuccess() {
        DeviceRegistrationToken token1 = DeviceRegistrationToken.builder()
                .id(UUID.randomUUID())
                .tenant(tenant)
                .tokenHash("hash1")
                .name("Token 1")
                .maxUses(10)
                .currentUses(3)
                .isActive(true)
                .createdBy(adminUser)
                .createdTs(Instant.now())
                .updatedTs(Instant.now())
                .build();

        DeviceRegistrationToken token2 = DeviceRegistrationToken.builder()
                .id(UUID.randomUUID())
                .tenant(tenant)
                .tokenHash("hash2")
                .name("Token 2")
                .currentUses(0)
                .isActive(true)
                .createdBy(adminUser)
                .createdTs(Instant.now())
                .updatedTs(Instant.now())
                .build();

        PageImpl<DeviceRegistrationToken> page = new PageImpl<>(List.of(token1, token2),
                PageRequest.of(0, 20), 2);

        when(tokenRepository.findByTenantId(eq(principal.getTenantId()), any(Pageable.class)))
                .thenReturn(page);

        PageResponse<DeviceTokenResponse> response = deviceTokenService.getTokens(principal, 0, 20);

        assertThat(response.getContent()).hasSize(2);
        assertThat(response.getTotalElements()).isEqualTo(2);
        // Raw tokens must NOT be returned in list
        assertThat(response.getContent().get(0).getToken()).isNull();
        assertThat(response.getContent().get(1).getToken()).isNull();
        assertThat(response.getContent().get(0).getName()).isEqualTo("Token 1");
        assertThat(response.getContent().get(1).getName()).isEqualTo("Token 2");
    }

    @Test
    @DisplayName("Deactivate token - sets isActive to false")
    void deactivateTokenSuccess() {
        UUID tokenId = UUID.randomUUID();
        DeviceRegistrationToken token = DeviceRegistrationToken.builder()
                .id(tokenId)
                .tenant(tenant)
                .tokenHash("somehash")
                .name("Deactivate Me")
                .currentUses(5)
                .isActive(true)
                .createdBy(adminUser)
                .createdTs(Instant.now())
                .updatedTs(Instant.now())
                .build();

        when(tokenRepository.findById(tokenId)).thenReturn(Optional.of(token));
        when(tokenRepository.save(any(DeviceRegistrationToken.class))).thenAnswer(i -> i.getArgument(0));

        deviceTokenService.deactivateToken(tokenId, principal, "127.0.0.1", "Test-Agent");

        verify(tokenRepository).save(argThat(t -> !t.getIsActive()));
        verify(auditService).logAction(eq(principal.getTenantId()), eq(principal.getUserId()),
                eq("DEVICE_TOKEN_DEACTIVATED"), eq("DEVICE_TOKENS"), eq(tokenId), anyMap(),
                eq("127.0.0.1"), eq("Test-Agent"), any());
    }

    @Test
    @DisplayName("Deactivate token - not found throws ResourceNotFoundException")
    void deactivateTokenNotFound() {
        UUID tokenId = UUID.randomUUID();
        when(tokenRepository.findById(tokenId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> deviceTokenService.deactivateToken(tokenId, principal, "127.0.0.1", "Test-Agent"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("Deactivate token - cross-tenant access returns not found")
    void deactivateTokenCrossTenant() {
        UUID tokenId = UUID.randomUUID();
        Tenant otherTenant = Tenant.builder()
                .id(UUID.randomUUID())
                .name("Other Tenant")
                .slug("other")
                .isActive(true)
                .settings(Map.of())
                .createdTs(Instant.now())
                .updatedTs(Instant.now())
                .build();

        DeviceRegistrationToken token = DeviceRegistrationToken.builder()
                .id(tokenId)
                .tenant(otherTenant)
                .tokenHash("somehash")
                .name("Other Token")
                .currentUses(0)
                .isActive(true)
                .createdBy(adminUser)
                .createdTs(Instant.now())
                .updatedTs(Instant.now())
                .build();

        when(tokenRepository.findById(tokenId)).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> deviceTokenService.deactivateToken(tokenId, principal, "127.0.0.1", "Test-Agent"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("not found");
    }
}
