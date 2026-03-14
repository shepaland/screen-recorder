package com.prg.controlplane.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "device_logs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @Column(name = "log_type", nullable = false, length = 100)
    private String logType;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "log_from_ts")
    private Instant logFromTs;

    @Column(name = "log_to_ts")
    private Instant logToTs;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    @Column(name = "requested_by")
    private UUID requestedBy;

    @PrePersist
    protected void onCreate() {
        if (uploadedAt == null) uploadedAt = Instant.now();
    }
}
