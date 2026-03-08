package com.prg.ingest.entity.catalog;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "app_aliases")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AppAlias {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "alias_type", nullable = false, length = 10)
    private AliasType aliasType;

    @Column(name = "original", nullable = false, length = 512)
    private String original;

    @Column(name = "display_name", nullable = false, length = 200)
    private String displayName;

    @Column(name = "icon_url", length = 1024)
    private String iconUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) id = UUID.randomUUID();
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public enum AliasType {
        APP, SITE
    }
}
