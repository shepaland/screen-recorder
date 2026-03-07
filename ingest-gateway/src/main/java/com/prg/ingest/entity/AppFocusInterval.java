package com.prg.ingest.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "app_focus_intervals")
@IdClass(AppFocusIntervalId.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AppFocusInterval {

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

    @Column(name = "process_name", nullable = false, length = 512)
    private String processName;

    @Column(name = "window_title", nullable = false, length = 2048)
    @Builder.Default
    private String windowTitle = "";

    @Column(name = "is_browser", nullable = false)
    @Builder.Default
    private boolean isBrowser = false;

    @Column(name = "browser_name", length = 100)
    private String browserName;

    @Column(length = 512)
    private String domain;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "duration_ms", nullable = false)
    @Builder.Default
    private Integer durationMs = 0;

    @Column(nullable = false, length = 50)
    @Builder.Default
    private String category = "uncategorized";

    @Column(name = "correlation_id")
    private UUID correlationId;

    @PrePersist
    protected void onCreate() {
        if (id == null) id = UUID.randomUUID();
        if (createdTs == null) createdTs = Instant.now();
        if (windowTitle == null) windowTitle = "";
        if (category == null) category = "uncategorized";
        if (durationMs == null) durationMs = 0;
    }
}
