package com.prg.auth.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SelectTenantRequest {

    private String oauthToken;

    @NotNull(message = "Tenant ID is required")
    private UUID tenantId;
}
