package com.prg.auth.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceLoginRequest {

    @NotBlank(message = "Username is required")
    @Size(min = 3, max = 255, message = "Username must be between 3 and 255 characters")
    private String username;

    @NotBlank(message = "Password is required")
    private String password;

    @NotBlank(message = "Registration token is required")
    private String registrationToken;

    @NotNull(message = "Device info is required")
    @Valid
    private DeviceInfo deviceInfo;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DeviceInfo {

        @NotBlank(message = "Hostname is required")
        @Size(max = 255, message = "Hostname must not exceed 255 characters")
        private String hostname;

        @Size(max = 255, message = "OS version must not exceed 255 characters")
        private String osVersion;

        @Size(max = 50, message = "Agent version must not exceed 50 characters")
        private String agentVersion;

        @NotBlank(message = "Hardware ID is required")
        @Size(max = 255, message = "Hardware ID must not exceed 255 characters")
        private String hardwareId;
    }
}
