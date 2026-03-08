package com.prg.auth.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "device_registration_tokens")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceRegistrationToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Column(name = "token_hash", nullable = false, unique = true, length = 255)
    private String tokenHash;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(name = "max_uses")
    private Integer maxUses;

    @Column(name = "current_uses", nullable = false)
    private Integer currentUses;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive;

    @Column(name = "encrypted_token")
    private String encryptedToken;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @Column(name = "created_ts", nullable = false, updatable = false)
    private Instant createdTs;

    @Column(name = "updated_ts", nullable = false)
    private Instant updatedTs;

    @PrePersist
    protected void onCreate() {
        if (createdTs == null) createdTs = Instant.now();
        if (updatedTs == null) updatedTs = Instant.now();
        if (isActive == null) isActive = true;
        if (currentUses == null) currentUses = 0;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedTs = Instant.now();
    }
}
