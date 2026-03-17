package com.prg.playback.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "recording_sessions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecordingSession {
    @Id
    private UUID id;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "device_id")
    private UUID deviceId;

    private String status;

    @Column(name = "started_ts")
    private Instant startedTs;

    @Column(name = "ended_ts")
    private Instant endedTs;

    @Column(name = "segment_count")
    private Integer segmentCount;

    @Column(name = "total_bytes")
    private Long totalBytes;

    @Column(name = "total_duration_ms")
    private Long totalDurationMs;
}
