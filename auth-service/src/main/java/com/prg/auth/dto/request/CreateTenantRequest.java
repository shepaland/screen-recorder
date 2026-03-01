package com.prg.auth.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
public class CreateTenantRequest {

    @NotBlank(message = "Tenant name is required")
    @Size(min = 2, max = 255, message = "Tenant name must be between 2 and 255 characters")
    private String name;

    @NotBlank(message = "Tenant slug is required")
    @Size(min = 3, max = 100, message = "Tenant slug must be between 3 and 100 characters")
    @Pattern(regexp = "^[a-z][a-z0-9-]*$", message = "Slug must be lowercase alphanumeric with dashes")
    private String slug;

    private Map<String, Object> settings;

    @NotNull(message = "Admin user is required")
    @Valid
    private AdminUser adminUser;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AdminUser {

        @NotBlank(message = "Admin username is required")
        @Size(min = 3, max = 255)
        private String username;

        @NotBlank(message = "Admin email is required")
        @jakarta.validation.constraints.Email
        private String email;

        @NotBlank(message = "Admin password is required")
        @Size(min = 8, max = 128)
        @Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).+$",
                 message = "Password must contain at least 1 uppercase, 1 lowercase letter and 1 digit")
        private String password;

        @Size(max = 255)
        private String firstName;

        @Size(max = 255)
        private String lastName;
    }
}
