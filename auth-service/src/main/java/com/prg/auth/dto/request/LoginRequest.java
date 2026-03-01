package com.prg.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginRequest {

    @NotBlank(message = "Username is required")
    @Size(min = 3, max = 255, message = "Username must be between 3 and 255 characters")
    private String username;

    @NotBlank(message = "Password is required")
    private String password;

    @NotBlank(message = "Tenant slug is required")
    @Size(min = 3, max = 100, message = "Tenant slug must be between 3 and 100 characters")
    private String tenantSlug;
}
