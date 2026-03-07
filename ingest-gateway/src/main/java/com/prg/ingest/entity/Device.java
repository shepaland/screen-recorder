package com.prg.ingest.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Read-only entity for devices table. Used to resolve device hostname
 * in recording list responses. Device lifecycle is managed by control-plane.
 */
@Entity
@Table(name = "devices")
@Data
@NoArgsConstructor
@AllArgsConstructor
@org.hibernate.annotations.Immutable
public class Device {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String hostname;

    @Column(length = 20, nullable = false)
    private String status;

    @Column(name = "os_version", length = 255)
    private String osVersion;

    @Column(name = "os_type", length = 20)
    private String osType;

    @Column(name = "timezone", length = 50)
    private String timezone;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive;

    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted;

    @Column(name = "created_ts", nullable = false, updatable = false)
    private Instant createdTs;
}
