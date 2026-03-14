package com.prg.controlplane.dto.response;

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
public class DeviceLogResponse {
    private UUID id;
    private String logType;
    private String content;
    private Instant logFromTs;
    private Instant logToTs;
    private Instant uploadedAt;
}
