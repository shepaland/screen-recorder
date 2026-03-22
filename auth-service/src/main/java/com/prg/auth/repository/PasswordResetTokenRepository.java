package com.prg.auth.repository;

import com.prg.auth.entity.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {

    Optional<PasswordResetToken> findByToken(String token);

    @Modifying
    @Query("UPDATE PasswordResetToken t SET t.isUsed = true WHERE t.userId = :userId AND t.isUsed = false")
    void invalidateAllByUserId(@Param("userId") UUID userId);

    @Query("SELECT COUNT(t) FROM PasswordResetToken t WHERE t.userId = :userId AND t.createdTs > :since")
    long countByUserIdSince(@Param("userId") UUID userId, @Param("since") Instant since);

    @Modifying
    @Query("DELETE FROM PasswordResetToken t WHERE t.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
