package com.prg.auth.dto.request;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateTenantRequest {

    @Size(min = 2, max = 255, message = "Tenant name must be between 2 and 255 characters")
    private String name;

    private Boolean isActive;

    private Map<String, Object> settings;
}
