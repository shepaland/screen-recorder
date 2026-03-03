package com.prg.auth.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserResponse {
    private UUID id;
    private UUID tenantId;
    private String username;
    private String email;
    private String firstName;
    private String lastName;
    private String authProvider;
    private String avatarUrl;
    private Boolean isActive;
    private List<RoleResponse> roles;
    private List<String> permissions;
    private Instant lastLoginTs;
    private Map<String, Object> settings;
    private Instant createdTs;
    private Instant updatedTs;
}
