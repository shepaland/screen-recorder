package com.prg.auth.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceResponse {
    private UUID id;
    private String hostname;
    private String osVersion;
    private String agentVersion;
    private String status;
    private Instant lastHeartbeatTs;
    private String ipAddress;
    private Boolean isActive;
    private Instant createdTs;
}
