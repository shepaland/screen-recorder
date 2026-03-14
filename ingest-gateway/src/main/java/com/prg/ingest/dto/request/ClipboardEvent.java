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
public class ClipboardEvent {

    @NotNull(message = "id is required")
    private UUID id;

    @NotNull(message = "timestamp is required")
    private Instant timestamp;

    @NotNull(message = "action is required")
    @Size(max = 10)
    private String action;

    @Size(max = 512)
    private String sourceProcess;

    @Size(max = 20)
    private String contentType;

    private Integer contentLength;

    private UUID sessionId;

    // Video timecode binding (nullable)
    private UUID segmentId;
    private Integer segmentOffsetMs;
}
