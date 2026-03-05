package com.prg.auth.dto.request;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateProfileRequest {

    @Size(max = 255, message = "First name must not exceed 255 characters")
    private String firstName;

    @Size(max = 255, message = "Last name must not exceed 255 characters")
    private String lastName;
}
