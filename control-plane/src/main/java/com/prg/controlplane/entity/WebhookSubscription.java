package com.prg.controlplane.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "webhook_subscriptions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebhookSubscription {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 2048)
    private String url;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "event_types", columnDefinition = "jsonb")
    private List<String> eventTypes;

    @Column(length = 256)
    private String secret;

    @Builder.Default
    private Boolean active = true;

    @Column(name = "created_ts")
    private Instant createdTs;

    @Column(name = "updated_ts")
    private Instant updatedTs;

    @Column(name = "created_by")
    private UUID createdBy;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdTs == null) createdTs = now;
        if (updatedTs == null) updatedTs = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedTs = Instant.now();
    }
}
