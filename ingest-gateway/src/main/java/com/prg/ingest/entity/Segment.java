package com.prg.ingest.entity;

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
    @Column(name = "created_ts", nullable = false, updatable = false)
    private Instant createdTs;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "sequence_num", nullable = false)
    private Integer sequenceNum;

    @Column(name = "s3_bucket", nullable = false)
    private String s3Bucket;

    @Column(name = "s3_key", nullable = false)
    private String s3Key;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "checksum_sha256")
    private String checksumSha256;

    @Column(nullable = false, length = 20)
    private String status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (id == null) id = UUID.randomUUID();
        if (createdTs == null) createdTs = now;
        if (status == null) status = "uploaded";
    }
}
