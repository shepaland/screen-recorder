package com.prg.auth.service;

import com.prg.auth.dto.request.CreateUserRequest;
import com.prg.auth.dto.response.UserResponse;
import com.prg.auth.entity.*;
import com.prg.auth.exception.DuplicateResourceException;
import com.prg.auth.exception.ResourceNotFoundException;
import com.prg.auth.repository.RoleRepository;
import com.prg.auth.repository.TenantRepository;
import com.prg.auth.repository.UserRepository;
import com.prg.auth.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private TenantRepository tenantRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private AuditService auditService;

    @InjectMocks
    private UserService userService;

    private Tenant tenant;
    private UUID tenantId;
    private UserPrincipal adminPrincipal;
    private Role operatorRole;
    private Permission viewPermission;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        tenant = Tenant.builder()
                .id(tenantId)
                .name("Test Tenant")
                .slug("test-tenant")
                .isActive(true)
                .settings(Map.of())
                .createdTs(Instant.now())
                .updatedTs(Instant.now())
                .build();

        adminPrincipal = UserPrincipal.builder()
                .userId(UUID.randomUUID())
                .tenantId(tenantId)
                .username("admin")
                .email("admin@test.com")
                .roles(List.of("TENANT_ADMIN"))
                .permissions(List.of("USERS:CREATE", "USERS:READ", "USERS:UPDATE", "USERS:DELETE"))
                .scopes(List.of("tenant"))
                .build();

        viewPermission = Permission.builder()
                .id(UUID.randomUUID())
                .code("DASHBOARD:VIEW")
                .name("View dashboard")
                .resource("DASHBOARD")
                .action("VIEW")
                .build();

        operatorRole = Role.builder()
                .id(UUID.randomUUID())
                .tenant(tenant)
                .code("OPERATOR")
                .name("Operator")
                .isSystem(true)
                .permissions(Set.of(viewPermission))
                .createdTs(Instant.now())
                .updatedTs(Instant.now())
                .build();
    }

    @Test
    @DisplayName("Create user success - returns created user with roles")
    void createUserSuccess() {
        CreateUserRequest request = CreateUserRequest.builder()
                .username("newuser")
                .email("newuser@test.com")
                .password("Password123")
                .firstName("New")
                .lastName("User")
                .roleIds(Set.of(operatorRole.getId()))
                .build();

        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(userRepository.existsByTenantIdAndUsername(tenantId, "newuser")).thenReturn(false);
        when(userRepository.existsByTenantIdAndEmail(tenantId, "newuser@test.com")).thenReturn(false);
        when(roleRepository.findByIdAndTenantId(operatorRole.getId(), tenantId)).thenReturn(Optional.of(operatorRole));
        when(passwordEncoder.encode("Password123")).thenReturn("$2a$12$encoded");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User u = invocation.getArgument(0);
            u.setId(UUID.randomUUID());
            u.setCreatedTs(Instant.now());
            u.setUpdatedTs(Instant.now());
            return u;
        });

        UserResponse response = userService.createUser(request, tenantId, adminPrincipal, "127.0.0.1", "Test-Agent");

        assertThat(response).isNotNull();
        assertThat(response.getUsername()).isEqualTo("newuser");
        assertThat(response.getEmail()).isEqualTo("newuser@test.com");
        assertThat(response.getFirstName()).isEqualTo("New");
        assertThat(response.getLastName()).isEqualTo("User");
        assertThat(response.getIsActive()).isTrue();

        verify(userRepository).save(any(User.class));
        verify(auditService).logAction(eq(tenantId), eq(adminPrincipal.getUserId()),
                eq("USER_CREATED"), eq("USERS"), any(UUID.class), anyMap(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("Create user failure - duplicate username throws DuplicateResourceException")
    void createUserDuplicateUsername() {
        CreateUserRequest request = CreateUserRequest.builder()
                .username("existing")
                .email("new@test.com")
                .password("Password123")
                .build();

        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(userRepository.existsByTenantIdAndUsername(tenantId, "existing")).thenReturn(true);

        assertThatThrownBy(() -> userService.createUser(request, tenantId, adminPrincipal, "127.0.0.1", "Test-Agent"))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("Username already exists");
    }

    @Test
    @DisplayName("Create user failure - duplicate email throws DuplicateResourceException")
    void createUserDuplicateEmail() {
        CreateUserRequest request = CreateUserRequest.builder()
                .username("newuser")
                .email("existing@test.com")
                .password("Password123")
                .build();

        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(userRepository.existsByTenantIdAndUsername(tenantId, "newuser")).thenReturn(false);
        when(userRepository.existsByTenantIdAndEmail(tenantId, "existing@test.com")).thenReturn(true);

        assertThatThrownBy(() -> userService.createUser(request, tenantId, adminPrincipal, "127.0.0.1", "Test-Agent"))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("Email already exists");
    }

    @Test
    @DisplayName("Get user by ID - tenant isolation: user from different tenant not found")
    void getUserTenantIsolation() {
        UUID userId = UUID.randomUUID();
        UUID differentTenantId = UUID.randomUUID();

        when(userRepository.findByIdAndTenantId(userId, differentTenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getUserById(userId, differentTenantId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("User not found");
    }

    @Test
    @DisplayName("Get user by ID - success returns full user response")
    void getUserByIdSuccess() {
        User user = User.builder()
                .id(UUID.randomUUID())
                .tenant(tenant)
                .username("testuser")
                .email("test@test.com")
                .passwordHash("hash")
                .firstName("Test")
                .lastName("User")
                .isActive(true)
                .roles(Set.of(operatorRole))
                .createdTs(Instant.now())
                .updatedTs(Instant.now())
                .build();

        when(userRepository.findByIdAndTenantId(user.getId(), tenantId)).thenReturn(Optional.of(user));

        UserResponse response = userService.getUserById(user.getId(), tenantId);

        assertThat(response).isNotNull();
        assertThat(response.getUsername()).isEqualTo("testuser");
        assertThat(response.getTenantId()).isEqualTo(tenantId);
        assertThat(response.getRoles()).hasSize(1);
        assertThat(response.getRoles().get(0).getCode()).isEqualTo("OPERATOR");
    }
}
