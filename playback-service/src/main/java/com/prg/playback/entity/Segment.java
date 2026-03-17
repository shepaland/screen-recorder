package com.prg.playback.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "segments")
@IdClass(SegmentId.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Segment {
    @Id
    private UUID id;

    @Id
    @Column(name = "created_ts", updatable = false)
    private Instant createdTs;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "device_id")
    private UUID deviceId;

    @Column(name = "session_id")
    private UUID sessionId;

    @Column(name = "sequence_num")
    private Integer sequenceNum;

    @Column(name = "s3_bucket")
    private String s3Bucket;

    @Column(name = "s3_key")
    private String s3Key;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @Column(name = "checksum_sha256")
    private String checksumSha256;

    private String status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;
}
