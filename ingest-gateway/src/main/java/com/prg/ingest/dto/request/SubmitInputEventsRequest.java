package com.prg.ingest.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubmitInputEventsRequest {

    @NotNull(message = "device_id is required")
    private UUID deviceId;

    @NotBlank(message = "username is required")
    @Size(max = 256, message = "username must not exceed 256 characters")
    private String username;

    @Valid
    @Size(max = 500, message = "mouse_clicks must not exceed 500 items")
    private List<MouseClickEvent> mouseClicks;

    @Valid
    @Size(max = 100, message = "keyboard_metrics must not exceed 100 items")
    private List<KeyboardMetricEvent> keyboardMetrics;

    @Valid
    @Size(max = 200, message = "scroll_events must not exceed 200 items")
    private List<ScrollEvent> scrollEvents;

    @Valid
    @Size(max = 50, message = "clipboard_events must not exceed 50 items")
    private List<ClipboardEvent> clipboardEvents;
}
