package com.prg.ingest.dto.response;

import lombok.*;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecordingDayResponse {
    private String date;          // YYYY-MM-DD in device timezone
    private int sessionCount;
    private int segmentCount;
    private long totalBytes;
    private long totalDurationMs;
    private boolean live;         // true if any session is still "active" that day
    private Instant firstStartedTs;
    private Instant lastEndedTs;
}
