package com.prg.auth.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "invitations")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Invitation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 255)
    private String email;

    @Column(name = "role_id", nullable = false)
    private UUID roleId;

    @Column(nullable = false, unique = true, length = 255)
    private String token;

    @Column(name = "invited_by", nullable = false)
    private UUID invitedBy;

    @Column(name = "accepted_ts")
    private Instant acceptedTs;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_ts", nullable = false)
    private Instant createdTs;

    @PrePersist
    protected void onCreate() {
        if (createdTs == null) createdTs = Instant.now();
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public boolean isAccepted() {
        return acceptedTs != null;
    }
}
