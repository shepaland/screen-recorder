package com.prg.auth.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "devices", uniqueConstraints = {
        @UniqueConstraint(name = "devices_tenant_id_hardware_id_key", columnNames = {"tenant_id", "hardware_id"})
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Device {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "registration_token_id")
    private DeviceRegistrationToken registrationToken;

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
    @Column(columnDefinition = "jsonb", nullable = false)
    @Builder.Default
    private Map<String, Object> settings = Map.of();

    @Column(name = "is_active", nullable = false)
    private Boolean isActive;

    @Column(name = "is_deleted", nullable = false)
    @Builder.Default
    private Boolean isDeleted = false;

    @Column(name = "deleted_ts")
    private Instant deletedTs;

    @Column(name = "created_ts", nullable = false, updatable = false)
    private Instant createdTs;

    @Column(name = "updated_ts", nullable = false)
    private Instant updatedTs;

    @PrePersist
    protected void onCreate() {
        if (createdTs == null) createdTs = Instant.now();
        if (updatedTs == null) updatedTs = Instant.now();
        if (isActive == null) isActive = true;
        if (isDeleted == null) isDeleted = false;
        if (status == null) status = "offline";
        if (settings == null) settings = Map.of();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedTs = Instant.now();
    }
}
