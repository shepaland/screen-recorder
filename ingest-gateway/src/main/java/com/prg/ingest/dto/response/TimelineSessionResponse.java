package com.prg.ingest.dto.response;

import lombok.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TimelineSessionResponse {
    private UUID sessionId;
    private String status;
    private Instant startedTs;
    private Instant endedTs;
    private int segmentCount;
    private long totalDurationMs;
    private long totalBytes;
    private List<TimelineSegmentResponse> segments;
}
