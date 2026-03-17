package com.prg.controlplane.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "webhook_deliveries")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebhookDelivery {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "subscription_id", nullable = false)
    private UUID subscriptionId;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Builder.Default
    @Column(length = 20)
    private String status = "pending";

    @Column(name = "response_code")
    private Integer responseCode;

    @Builder.Default
    private Integer attempts = 0;

    @Column(name = "last_attempt_ts")
    private Instant lastAttemptTs;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "created_ts")
    private Instant createdTs;

    @PrePersist
    void onCreate() {
        if (createdTs == null) createdTs = Instant.now();
    }
}
