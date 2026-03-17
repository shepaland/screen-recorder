package com.prg.controlplane.kafka.event;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
public class CommandIssuedEvent {
    private UUID commandId;
    private String commandType;
    private UUID tenantId;
    private UUID deviceId;
    private Map<String, Object> payload;
    private UUID createdBy;
    private Instant createdAt;
    private Instant expiresAt;
}
