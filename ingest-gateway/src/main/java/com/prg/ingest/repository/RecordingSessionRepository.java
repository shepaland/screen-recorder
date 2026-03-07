package com.prg.ingest.repository;

import com.prg.ingest.entity.RecordingSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RecordingSessionRepository extends JpaRepository<RecordingSession, UUID> {

    Optional<RecordingSession> findByIdAndTenantId(UUID id, UUID tenantId);

    Optional<RecordingSession> findByDeviceIdAndTenantIdAndStatus(UUID deviceId, UUID tenantId, String status);

    boolean existsByDeviceIdAndTenantIdAndStatus(UUID deviceId, UUID tenantId, String status);

    @Query(value = """
            SELECT rs.* FROM recording_sessions rs
            WHERE rs.tenant_id = :tenantId
              AND (CAST(:status AS varchar) IS NULL OR rs.status = :status)
              AND (CAST(:deviceId AS uuid) IS NULL OR rs.device_id = :deviceId)
              AND (CAST(:fromTs AS timestamptz) IS NULL OR rs.started_ts >= :fromTs)
              AND (CAST(:toTs AS timestamptz) IS NULL OR rs.started_ts <= :toTs)
            ORDER BY rs.started_ts DESC
            """,
            countQuery = """
            SELECT COUNT(*) FROM recording_sessions rs
            WHERE rs.tenant_id = :tenantId
              AND (CAST(:status AS varchar) IS NULL OR rs.status = :status)
              AND (CAST(:deviceId AS uuid) IS NULL OR rs.device_id = :deviceId)
              AND (CAST(:fromTs AS timestamptz) IS NULL OR rs.started_ts >= :fromTs)
              AND (CAST(:toTs AS timestamptz) IS NULL OR rs.started_ts <= :toTs)
            """,
            nativeQuery = true)
    Page<RecordingSession> findByTenantIdWithFilters(
            @Param("tenantId") UUID tenantId,
            @Param("status") String status,
            @Param("deviceId") UUID deviceId,
            @Param("fromTs") Instant fromTs,
            @Param("toTs") Instant toTs,
            Pageable pageable);

    @Query(value = """
            SELECT rs.* FROM recording_sessions rs
            WHERE rs.device_id = :deviceId
              AND rs.tenant_id = :tenantId
              AND DATE(rs.started_ts AT TIME ZONE :tz) = CAST(:date AS date)
            ORDER BY rs.started_ts ASC
            """, nativeQuery = true)
    List<RecordingSession> findSessionsByDeviceIdAndDate(
            @Param("deviceId") UUID deviceId,
            @Param("tenantId") UUID tenantId,
            @Param("tz") String timezone,
            @Param("date") String date);
}
