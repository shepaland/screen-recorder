package com.prg.controlplane.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "device_commands")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceCommand {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @Column(name = "command_type", nullable = false, length = 50)
    private String commandType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> payload = new HashMap<>();

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "delivered_ts")
    private Instant deliveredTs;

    @Column(name = "acknowledged_ts")
    private Instant acknowledgedTs;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result", columnDefinition = "jsonb")
    private Map<String, Object> result;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_ts", nullable = false, updatable = false)
    private Instant createdTs;

    @PrePersist
    protected void onCreate() {
        if (createdTs == null) createdTs = Instant.now();
        if (status == null) status = "pending";
        if (expiresAt == null) expiresAt = Instant.now().plusSeconds(86400); // 24 hours
    }
}
