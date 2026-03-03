package com.prg.auth.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "oauth_identities")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OAuthIdentity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 50)
    private String provider;

    @Column(name = "provider_sub", nullable = false, length = 255)
    private String providerSub;

    @Column(nullable = false, length = 255)
    private String email;

    @Column(length = 255)
    private String name;

    @Column(name = "avatar_url", length = 1024)
    private String avatarUrl;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_attributes", columnDefinition = "jsonb", nullable = false)
    @Builder.Default
    private Map<String, Object> rawAttributes = new HashMap<>();

    @Column(name = "created_ts", nullable = false, updatable = false)
    private Instant createdTs;

    @Column(name = "updated_ts", nullable = false)
    private Instant updatedTs;

    @PrePersist
    protected void onCreate() {
        if (createdTs == null) createdTs = Instant.now();
        if (updatedTs == null) updatedTs = Instant.now();
        if (rawAttributes == null) rawAttributes = new HashMap<>();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedTs = Instant.now();
    }
}
