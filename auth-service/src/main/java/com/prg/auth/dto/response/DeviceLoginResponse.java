package com.prg.auth.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceLoginResponse {
    private String accessToken;
    private String refreshToken;
    private String tokenType;
    private long expiresIn;
    private UUID deviceId;
    private String deviceStatus;
    private UserResponse user;
    private ServerConfig serverConfig;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ServerConfig {
        private int heartbeatIntervalSec;
        private int segmentDurationSec;
        private int captureFps;
        private String quality;
        private String ingestBaseUrl;
        private String controlPlaneBaseUrl;
        private String resolution;
        private Integer sessionMaxDurationMin;
        /** @deprecated Use sessionMaxDurationMin instead. Kept for backward compatibility with older agents. */
        private Integer sessionMaxDurationHours;
        private Boolean autoStart;
        private Boolean recordingEnabled;
    }
}
