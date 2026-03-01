package com.prg.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateDeviceTokenRequest {

    @NotBlank(message = "Token name is required")
    @Size(min = 1, max = 255, message = "Token name must be between 1 and 255 characters")
    private String name;

    private Integer maxUses;

    private Instant expiresAt;
}
