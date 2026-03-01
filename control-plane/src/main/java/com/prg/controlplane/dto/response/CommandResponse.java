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
public class CommandResponse {
    private UUID id;
    private UUID deviceId;
    private String commandType;
    private Map<String, Object> payload;
    private String status;
    private UUID createdBy;
    private Instant deliveredTs;
    private Instant acknowledgedTs;
    private Map<String, Object> result;
    private Instant expiresAt;
    private Instant createdTs;
}
