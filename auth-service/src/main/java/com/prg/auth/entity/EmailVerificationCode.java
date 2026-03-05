package com.prg.auth.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "email_verification_code")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmailVerificationCode {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 255)
    private String email;

    @Column(name = "code_hash", nullable = false, length = 64)
    private String codeHash;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String purpose = "register";

    @Column(nullable = false, length = 255)
    private String fingerprint;

    @Column(nullable = false)
    @Builder.Default
    private Integer attempts = 0;

    @Column(name = "max_attempts", nullable = false)
    @Builder.Default
    private Integer maxAttempts = 10;

    @Column(name = "is_used", nullable = false)
    @Builder.Default
    private Boolean isUsed = false;

    @Column(name = "is_blocked", nullable = false)
    @Builder.Default
    private Boolean isBlocked = false;

    @Column(name = "blocked_until")
    private Instant blockedUntil;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_ts", nullable = false, updatable = false)
    private Instant createdTs;

    @PrePersist
    protected void onCreate() {
        if (createdTs == null) createdTs = Instant.now();
        if (attempts == null) attempts = 0;
        if (maxAttempts == null) maxAttempts = 10;
        if (isUsed == null) isUsed = false;
        if (isBlocked == null) isBlocked = false;
        if (purpose == null) purpose = "register";
    }
}
