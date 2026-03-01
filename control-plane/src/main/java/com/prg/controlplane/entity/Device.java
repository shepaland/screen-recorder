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
@Table(name = "devices")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Device {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "registration_token_id")
    private UUID registrationTokenId;

    @Column(nullable = false, length = 255)
    private String hostname;

    @Column(name = "os_version", length = 255)
    private String osVersion;

    @Column(name = "agent_version", length = 50)
    private String agentVersion;

    @Column(name = "hardware_id", length = 255)
    private String hardwareId;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "last_heartbeat_ts")
    private Instant lastHeartbeatTs;

    @Column(name = "last_recording_ts")
    private Instant lastRecordingTs;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "settings", columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> settings = new HashMap<>();

    @Column(name = "is_active", nullable = false)
    private Boolean isActive;

    @Column(name = "created_ts", nullable = false, updatable = false)
    private Instant createdTs;

    @Column(name = "updated_ts", nullable = false)
    private Instant updatedTs;

    @PrePersist
    protected void onCreate() {
        if (createdTs == null) createdTs = Instant.now();
        if (updatedTs == null) updatedTs = Instant.now();
        if (isActive == null) isActive = true;
        if (status == null) status = "offline";
    }

    @PreUpdate
    protected void onUpdate() {
        updatedTs = Instant.now();
    }
}
