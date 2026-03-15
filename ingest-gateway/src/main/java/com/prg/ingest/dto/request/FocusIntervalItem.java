package com.prg.ingest.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
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
public class FocusIntervalItem {

    @NotNull(message = "interval id is required")
    private UUID id;

    @NotBlank(message = "process_name is required")
    @Size(max = 512, message = "process_name must not exceed 512 characters")
    private String processName;

    @Size(max = 2048, message = "window_title must not exceed 2048 characters")
    private String windowTitle;

    @NotNull(message = "is_browser is required")
    private Boolean isBrowser;

    @Size(max = 100, message = "browser_name must not exceed 100 characters")
    private String browserName;

    @Size(max = 512, message = "domain must not exceed 512 characters")
    private String domain;

    @NotNull(message = "started_at is required")
    private Instant startedAt;

    private Instant endedAt;

    @NotNull(message = "duration_ms is required")
    @Min(value = 0, message = "duration_ms must be >= 0")
    private Integer durationMs;

    private UUID sessionId;

    // Window geometry (nullable, backwards compatible)
    private Integer windowX;
    private Integer windowY;
    private Integer windowWidth;
    private Integer windowHeight;
    private Boolean isMaximized;
    private Boolean isFullscreen;
    private Integer monitorIndex;

    // Idle detection — true if no user input during this interval
    private Boolean isIdle;
}
