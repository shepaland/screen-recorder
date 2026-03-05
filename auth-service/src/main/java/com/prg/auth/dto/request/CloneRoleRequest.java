package com.prg.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CloneRoleRequest {

    @NotBlank(message = "Role code is required")
    @Size(min = 3, max = 100, message = "Role code must be between 3 and 100 characters")
    @Pattern(regexp = "^[A-Z][A-Z0-9_]*$", message = "Role code must be UPPER_SNAKE_CASE")
    private String code;

    @NotBlank(message = "Role name is required")
    @Size(min = 3, max = 255, message = "Role name must be between 3 and 255 characters")
    private String name;

    @Size(max = 1000, message = "Description must not exceed 1000 characters")
    private String description;
}
