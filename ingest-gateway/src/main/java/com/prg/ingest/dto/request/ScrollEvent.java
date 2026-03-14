package com.prg.ingest.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
public class ScrollEvent {

    @NotNull(message = "id is required")
    private UUID id;

    @NotNull(message = "interval_start is required")
    private Instant intervalStart;

    @NotNull(message = "interval_end is required")
    private Instant intervalEnd;

    @NotNull(message = "direction is required")
    @Size(max = 10)
    private String direction;

    @NotNull(message = "total_delta is required")
    private Integer totalDelta;

    @NotNull(message = "event_count is required")
    private Integer eventCount;

    @Size(max = 512)
    private String processName;

    private UUID sessionId;

    // Video timecode binding (nullable)
    private UUID segmentId;
    private Integer segmentOffsetMs;
}
