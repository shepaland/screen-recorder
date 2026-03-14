package com.prg.ingest.dto.response;

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
public class InputEventResponse {
    private UUID id;
    private Instant eventTs;
    private String eventType;
    private String username;
    private UUID deviceId;
    private String deviceHostname;
    private UUID sessionId;
    private UUID segmentId;
    private Integer segmentOffsetMs;

    // Mouse click
    private Integer clickX;
    private Integer clickY;
    private String clickButton;
    private String clickType;

    // UI element
    private String uiElementType;
    private String uiElementName;

    // Keyboard
    private Integer keystrokeCount;
    private Boolean hasTypingBurst;

    // Scroll
    private String scrollDirection;
    private Integer scrollTotalDelta;
    private Integer scrollEventCount;

    // Clipboard
    private String clipboardAction;
    private String clipboardContentType;
    private Integer clipboardContentLength;

    // Context
    private String processName;
    private String windowTitle;
}
