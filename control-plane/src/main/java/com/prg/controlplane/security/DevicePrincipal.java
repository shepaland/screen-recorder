package com.prg.controlplane.security;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DevicePrincipal {

    private UUID userId;
    private UUID tenantId;
    private UUID deviceId;

    @Builder.Default
    private List<String> roles = List.of();

    @Builder.Default
    private List<String> permissions = List.of();

    @Builder.Default
    private List<String> scopes = List.of();

    public boolean hasPermission(String permission) {
        return permissions.contains(permission);
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    public boolean hasScope(String scope) {
        return scopes.contains(scope);
    }
}
