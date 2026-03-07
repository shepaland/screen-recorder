package com.prg.ingest.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "device_audit_events")
@IdClass(DeviceAuditEventId.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceAuditEvent {

    @Id
    private UUID id;

    @Id
    @Column(name = "created_ts", nullable = false, updatable = false)
    private Instant createdTs;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @Column(name = "session_id")
    private UUID sessionId;

    @Column(name = "event_type", nullable = false, length = 30)
    private String eventType;

    @Column(name = "event_ts", nullable = false)
    private Instant eventTs;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> details = Map.of();

    @Column(name = "correlation_id")
    private UUID correlationId;

    @PrePersist
    protected void onCreate() {
        if (id == null) id = UUID.randomUUID();
        if (createdTs == null) createdTs = Instant.now();
        if (details == null) details = Map.of();
    }
}
