package com.prg.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
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
public class DeviceRefreshRequest {

    @NotBlank(message = "Refresh token is required")
    private String refreshToken;

    @NotNull(message = "Device ID is required")
    private UUID deviceId;
}
