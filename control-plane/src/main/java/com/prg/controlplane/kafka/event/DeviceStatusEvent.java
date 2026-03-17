package com.prg.controlplane.kafka.event;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class DeviceStatusEvent {
    private UUID eventId;
    private String eventType;
    private Instant timestamp;
    private UUID tenantId;
    private UUID deviceId;
    private String hostname;
    private String agentVersion;
}
