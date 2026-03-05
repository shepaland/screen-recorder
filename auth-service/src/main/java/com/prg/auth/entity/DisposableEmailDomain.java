package com.prg.auth.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "disposable_email_domain")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DisposableEmailDomain {

    @Id
    @Column(nullable = false, length = 255)
    private String domain;

    @Column(name = "added_ts", nullable = false, updatable = false)
    private Instant addedTs;

    @PrePersist
    protected void onCreate() {
        if (addedTs == null) addedTs = Instant.now();
    }
}
