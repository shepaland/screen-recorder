package com.prg.auth.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "tenant_memberships", uniqueConstraints = {
        @UniqueConstraint(name = "tenant_memberships_user_id_tenant_id_key", columnNames = {"user_id", "tenant_id"})
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(exclude = {"roles"})
@ToString(exclude = {"roles"})
public class TenantMembership {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "membership_roles",
            joinColumns = @JoinColumn(name = "membership_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id")
    )
    @Builder.Default
    private Set<Role> roles = new HashSet<>();

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "is_default", nullable = false)
    @Builder.Default
    private Boolean isDefault = false;

    @Column(name = "joined_ts", nullable = false)
    private Instant joinedTs;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invited_by")
    private User invitedBy;

    @Column(name = "created_ts", nullable = false)
    private Instant createdTs;

    @Column(name = "updated_ts", nullable = false)
    private Instant updatedTs;

    @PrePersist
    protected void onCreate() {
        if (joinedTs == null) joinedTs = Instant.now();
        if (createdTs == null) createdTs = Instant.now();
        if (updatedTs == null) updatedTs = Instant.now();
        if (isActive == null) isActive = true;
        if (isDefault == null) isDefault = false;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedTs = Instant.now();
    }
}
