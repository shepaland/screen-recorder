package com.prg.controlplane.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "device_status_log")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceStatusLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @Column(name = "previous_status", length = 20)
    private String previousStatus;

    @Column(name = "new_status", nullable = false, length = 20)
    private String newStatus;

    @Column(name = "changed_ts", nullable = false)
    private Instant changedTs;

    @Column(name = "trigger", nullable = false, length = 30)
    private String trigger;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details", columnDefinition = "jsonb")
    private Map<String, Object> details;

    @PrePersist
    protected void onCreate() {
        if (changedTs == null) changedTs = Instant.now();
        if (trigger == null) trigger = "heartbeat";
    }
}
