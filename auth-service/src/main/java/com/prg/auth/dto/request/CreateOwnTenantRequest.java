package com.prg.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
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
public class CreateOwnTenantRequest {

    @NotBlank(message = "Tenant name is required")
    @Size(min = 2, max = 255, message = "Tenant name must be between 2 and 255 characters")
    private String name;

    @NotBlank(message = "Tenant slug is required")
    @Size(min = 3, max = 100, message = "Tenant slug must be between 3 and 100 characters")
    @Pattern(regexp = "^[a-z][a-z0-9-]*$", message = "Slug must start with letter, contain only lowercase letters, numbers and dashes")
    private String slug;

    private Map<String, Object> settings;
}
