package com.prg.controlplane.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceDetailResponse {
    private UUID id;
    private UUID tenantId;
    private UUID userId;
    private UUID registrationTokenId;
    private String hostname;
    private String osVersion;
    private String osType;
    private String agentVersion;
    private String hardwareId;
    private String status;
    private Instant lastHeartbeatTs;
    private Instant lastRecordingTs;
    private String ipAddress;
    private String timezone;
    private Map<String, Object> settings;
    private Boolean isActive;
    private Boolean isDeleted;
    private Instant deletedTs;
    private Instant createdTs;
    private Instant updatedTs;
    private List<CommandResponse> recentCommands;
}
