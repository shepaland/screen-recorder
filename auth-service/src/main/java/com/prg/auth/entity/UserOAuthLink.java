package com.prg.auth.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_oauth_links")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(exclude = {"user", "oauthIdentity", "linkedBy"})
@ToString(exclude = {"user", "oauthIdentity", "linkedBy"})
public class UserOAuthLink {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "oauth_id", nullable = false)
    private OAuthIdentity oauthIdentity;

    @Column(name = "linked_ts", nullable = false, updatable = false)
    private Instant linkedTs;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "linked_by")
    private User linkedBy;

    @PrePersist
    protected void onCreate() {
        if (linkedTs == null) linkedTs = Instant.now();
    }
}
