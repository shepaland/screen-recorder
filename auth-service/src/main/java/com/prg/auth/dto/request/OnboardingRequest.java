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
public class OnboardingRequest {

    @NotBlank(message = "Company name is required")
    @Size(min = 2, max = 255, message = "Company name must be between 2 and 255 characters")
    private String companyName;

    @NotBlank(message = "Slug is required")
    @Size(min = 3, max = 100, message = "Slug must be between 3 and 100 characters")
    @Pattern(regexp = "^[a-z][a-z0-9-]*$",
            message = "Slug must start with a letter and contain only lowercase letters, digits and hyphens")
    private String slug;

    @Size(max = 255, message = "First name must not exceed 255 characters")
    private String firstName;

    @Size(max = 255, message = "Last name must not exceed 255 characters")
    private String lastName;
}
