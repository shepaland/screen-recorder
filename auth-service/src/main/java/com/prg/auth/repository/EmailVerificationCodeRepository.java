package com.prg.auth.repository;

import com.prg.auth.entity.EmailVerificationCode;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EmailVerificationCodeRepository extends JpaRepository<EmailVerificationCode, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM EmailVerificationCode e WHERE e.id = :id")
    Optional<EmailVerificationCode> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Find the most recent code for a given email, to check cooldown.
     */
    @Query("SELECT e FROM EmailVerificationCode e WHERE e.email = :email " +
           "ORDER BY e.createdTs DESC LIMIT 1")
    Optional<EmailVerificationCode> findLatestByEmail(@Param("email") String email);

    /**
     * Count codes sent for an email within a time window, for rate limiting.
     */
    @Query("SELECT COUNT(e) FROM EmailVerificationCode e WHERE e.email = :email " +
           "AND e.createdTs > :since")
    long countByEmailSince(@Param("email") String email, @Param("since") Instant since);

    /**
     * Invalidate all unused codes for an email (mark as used).
     */
    @Modifying
    @Query("UPDATE EmailVerificationCode e SET e.isUsed = true " +
           "WHERE e.email = :email AND e.isUsed = false")
    void invalidateAllByEmail(@Param("email") String email);

    /**
     * Delete expired codes older than a given timestamp (cleanup task).
     */
    @Modifying
    @Query("DELETE FROM EmailVerificationCode e WHERE e.expiresAt < :before")
    int deleteExpiredBefore(@Param("before") Instant before);
}
