package com.prg.ingest.dto.response;

import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TimelineSegmentResponse {
    private UUID id;
    private int sequenceNum;
    private long durationMs;
    private long sizeBytes;
    private String status;
    private String s3Key;
    private Instant recordedAt;
}
