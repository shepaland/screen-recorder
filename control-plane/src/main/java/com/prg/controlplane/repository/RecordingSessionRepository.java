package com.prg.controlplane.repository;

import com.prg.controlplane.entity.RecordingSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

@Repository
public interface RecordingSessionRepository extends JpaRepository<RecordingSession, UUID> {

    /**
     * Interrupt all active recording sessions for a device.
     * Used during device deactivation to cleanly terminate ongoing recordings.
     *
     * @return number of sessions interrupted
     */
    @Modifying
    @Query("""
            UPDATE RecordingSession rs
            SET rs.status = 'interrupted', rs.endedTs = :now, rs.updatedTs = :now
            WHERE rs.deviceId = :deviceId
              AND rs.tenantId = :tenantId
              AND rs.status = 'active'
            """)
    int interruptActiveSessionsByDeviceId(
            @Param("deviceId") UUID deviceId,
            @Param("tenantId") UUID tenantId,
            @Param("now") Instant now);
}
