package com.prg.ingest.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "recording_sessions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecordingSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @Column(name = "user_id")
    private UUID userId;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "started_ts", nullable = false)
    private Instant startedTs;

    @Column(name = "ended_ts")
    private Instant endedTs;

    @Column(name = "segment_count", nullable = false)
    @Builder.Default
    private Integer segmentCount = 0;

    @Column(name = "total_bytes", nullable = false)
    @Builder.Default
    private Long totalBytes = 0L;

    @Column(name = "total_duration_ms", nullable = false)
    @Builder.Default
    private Long totalDurationMs = 0L;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Column(name = "created_ts", nullable = false, updatable = false)
    private Instant createdTs;

    @Column(name = "updated_ts", nullable = false)
    private Instant updatedTs;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdTs == null) createdTs = now;
        if (updatedTs == null) updatedTs = now;
        if (startedTs == null) startedTs = now;
        if (status == null) status = "active";
        if (segmentCount == null) segmentCount = 0;
        if (totalBytes == null) totalBytes = 0L;
        if (totalDurationMs == null) totalDurationMs = 0L;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedTs = Instant.now();
    }
}
