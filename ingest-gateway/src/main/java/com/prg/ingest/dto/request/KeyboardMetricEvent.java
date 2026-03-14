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
public class KeyboardMetricEvent {

    @NotNull(message = "id is required")
    private UUID id;

    @NotNull(message = "interval_start is required")
    private Instant intervalStart;

    @NotNull(message = "interval_end is required")
    private Instant intervalEnd;

    @NotNull(message = "keystroke_count is required")
    private Integer keystrokeCount;

    @NotNull(message = "has_typing_burst is required")
    private Boolean hasTypingBurst;

    @Size(max = 512)
    private String processName;

    @Size(max = 2048)
    private String windowTitle;

    private UUID sessionId;

    // Video timecode binding (nullable)
    private UUID segmentId;
    private Integer segmentOffsetMs;
}
