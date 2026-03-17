package com.prg.search.dto;

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
public class SegmentConfirmedEvent {
    private UUID eventId;
    private Instant timestamp;
    private UUID tenantId;
    private UUID deviceId;
    private UUID sessionId;
    private UUID segmentId;
    private Integer sequenceNum;
    private String s3Key;
    private Long sizeBytes;
    private Integer durationMs;
    private String checksumSha256;
    private Map<String, Object> metadata;
}
