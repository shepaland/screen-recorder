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
public class MouseClickEvent {

    @NotNull(message = "id is required")
    private UUID id;

    @NotNull(message = "timestamp is required")
    private Instant timestamp;

    @NotNull(message = "x is required")
    private Integer x;

    @NotNull(message = "y is required")
    private Integer y;

    @NotNull(message = "button is required")
    @Size(max = 10)
    private String button;

    @NotNull(message = "click_type is required")
    @Size(max = 10)
    private String clickType;

    @Size(max = 512)
    private String processName;

    @Size(max = 2048)
    private String windowTitle;

    private UUID sessionId;

    // UI element context (nullable)
    @Size(max = 50)
    private String uiElementType;

    @Size(max = 200)
    private String uiElementName;

    @Size(max = 100)
    private String uiElementClass;

    @Size(max = 200)
    private String uiAutomationId;

    // Video timecode binding (nullable)
    private UUID segmentId;
    private Integer segmentOffsetMs;
}
