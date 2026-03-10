package com.prg.controlplane.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceStatusLogResponse {
    private UUID id;
    private UUID deviceId;
    private String previousStatus;
    private String newStatus;
    private Instant changedTs;
    private String trigger;
    private Map<String, Object> details;
}
