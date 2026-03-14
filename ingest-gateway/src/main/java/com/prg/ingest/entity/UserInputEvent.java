package com.prg.ingest.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_input_events")
@IdClass(UserInputEventId.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserInputEvent {

    @Id
    private UUID id;

    @Id
    @Column(name = "created_ts", nullable = false, updatable = false)
    private Instant createdTs;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @Column(nullable = false, length = 256)
    private String username;

    @Column(name = "session_id")
    private UUID sessionId;

    @Column(name = "event_type", nullable = false, length = 30)
    private String eventType;

    @Column(name = "event_ts", nullable = false)
    private Instant eventTs;

    @Column(name = "event_end_ts")
    private Instant eventEndTs;

    // Mouse click fields
    @Column(name = "click_x")
    private Integer clickX;

    @Column(name = "click_y")
    private Integer clickY;

    @Column(name = "click_button", length = 10)
    private String clickButton;

    @Column(name = "click_type", length = 10)
    private String clickType;

    // UI element context
    @Column(name = "ui_element_type", length = 50)
    private String uiElementType;

    @Column(name = "ui_element_name", length = 200)
    private String uiElementName;

    @Column(name = "ui_element_class", length = 100)
    private String uiElementClass;

    @Column(name = "ui_automation_id", length = 200)
    private String uiAutomationId;

    // Keyboard metric fields
    @Column(name = "keystroke_count")
    private Integer keystrokeCount;

    @Column(name = "has_typing_burst")
    private Boolean hasTypingBurst;

    // Scroll fields
    @Column(name = "scroll_direction", length = 10)
    private String scrollDirection;

    @Column(name = "scroll_total_delta")
    private Integer scrollTotalDelta;

    @Column(name = "scroll_event_count")
    private Integer scrollEventCount;

    // Clipboard fields
    @Column(name = "clipboard_action", length = 10)
    private String clipboardAction;

    @Column(name = "clipboard_content_type", length = 20)
    private String clipboardContentType;

    @Column(name = "clipboard_content_length")
    private Integer clipboardContentLength;

    // Context
    @Column(name = "process_name", length = 512)
    private String processName;

    @Column(name = "window_title", length = 2048)
    private String windowTitle;

    // Video timecode binding
    @Column(name = "segment_id")
    private UUID segmentId;

    @Column(name = "segment_offset_ms")
    private Integer segmentOffsetMs;

    // Correlation
    @Column(name = "correlation_id")
    private UUID correlationId;

    @PrePersist
    protected void onCreate() {
        if (id == null) id = UUID.randomUUID();
        if (createdTs == null) createdTs = Instant.now();
    }
}
