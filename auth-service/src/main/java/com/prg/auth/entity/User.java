package com.prg.auth.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "users", uniqueConstraints = {
        @UniqueConstraint(name = "uq_users_username_global", columnNames = {"username"}),
        @UniqueConstraint(name = "uq_users_email_global", columnNames = {"email"})
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(exclude = {"roles", "memberships"})
@ToString(exclude = {"roles", "memberships"})
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Column(nullable = false, length = 255)
    private String username;

    @Column(nullable = false, length = 255)
    private String email;

    @Column(name = "password_hash", length = 255)
    private String passwordHash;

    @Column(name = "auth_provider", nullable = false, length = 20)
    @Builder.Default
    private String authProvider = "password";

    @Column(name = "first_name", length = 255)
    private String firstName;

    @Column(name = "last_name", length = 255)
    private String lastName;

    @Column(name = "email_verified", nullable = false)
    @Builder.Default
    private Boolean emailVerified = false;

    @Column(name = "is_password_set", nullable = false)
    @Builder.Default
    private Boolean isPasswordSet = true;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive;

    @Column(name = "last_login_ts")
    private Instant lastLoginTs;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "user_roles",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id")
    )
    @Builder.Default
    private Set<Role> roles = new HashSet<>();

    @OneToMany(mappedBy = "user", fetch = FetchType.LAZY)
    @Builder.Default
    private Set<TenantMembership> memberships = new HashSet<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    @Builder.Default
    private Map<String, Object> settings = new HashMap<>();

    @Column(name = "created_ts", nullable = false, updatable = false)
    private Instant createdTs;

    @Column(name = "updated_ts", nullable = false)
    private Instant updatedTs;

    @PrePersist
    protected void onCreate() {
        normalizeEmailUsername();
        if (createdTs == null) createdTs = Instant.now();
        if (updatedTs == null) updatedTs = Instant.now();
        if (isActive == null) isActive = true;
        if (authProvider == null) authProvider = "password";
        if (emailVerified == null) emailVerified = false;
        if (isPasswordSet == null) isPasswordSet = true;
        if (settings == null) settings = new HashMap<>();
    }

    @PreUpdate
    protected void onUpdate() {
        normalizeEmailUsername();
        updatedTs = Instant.now();
    }

    /** Username always equals email, both normalized to lowercase+trim. */
    private void normalizeEmailUsername() {
        if (email != null) {
            email = email.trim().toLowerCase();
            username = email;
        }
    }
}
