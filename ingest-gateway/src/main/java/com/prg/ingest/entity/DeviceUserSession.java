package com.prg.ingest.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "device_user_sessions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceUserSession {

    @Id
    @Column(nullable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @Column(nullable = false, length = 256)
    private String username;

    @Column(name = "windows_domain", length = 256)
    private String windowsDomain;

    @Column(name = "display_name", length = 512)
    private String displayName;

    @Column(name = "first_seen_ts", nullable = false)
    private Instant firstSeenTs;

    @Column(name = "last_seen_ts", nullable = false)
    private Instant lastSeenTs;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean isActive = true;

    @Column(name = "created_ts", nullable = false, updatable = false)
    private Instant createdTs;

    @Column(name = "updated_ts", nullable = false)
    private Instant updatedTs;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (id == null) id = UUID.randomUUID();
        if (firstSeenTs == null) firstSeenTs = now;
        if (lastSeenTs == null) lastSeenTs = now;
        if (createdTs == null) createdTs = now;
        if (updatedTs == null) updatedTs = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedTs = Instant.now();
    }
}
