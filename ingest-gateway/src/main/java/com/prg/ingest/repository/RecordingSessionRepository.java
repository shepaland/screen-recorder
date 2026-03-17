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
            SELECT rs.*,
                   d.hostname AS device_hostname,
                   (SELECT dae.username FROM device_audit_events dae
                    WHERE dae.device_id = rs.device_id AND dae.tenant_id = rs.tenant_id
                      AND dae.username IS NOT NULL AND dae.username != ''
                      AND dae.event_ts <= rs.started_ts + INTERVAL '5 minutes'
                    ORDER BY dae.event_ts DESC LIMIT 1) AS employee_name
            FROM recording_sessions rs
            LEFT JOIN devices d ON d.id = rs.device_id AND d.tenant_id = rs.tenant_id
            WHERE rs.tenant_id = :tenantId
              AND (CAST(:status AS varchar) IS NULL OR rs.status = :status)
              AND (CAST(:deviceId AS uuid) IS NULL OR rs.device_id = :deviceId)
              AND (CAST(:fromTs AS timestamptz) IS NULL OR rs.started_ts >= :fromTs)
              AND (CAST(:toTs AS timestamptz) IS NULL OR rs.started_ts <= :toTs)
              AND (CAST(:search AS varchar) IS NULL
                   OR d.hostname ILIKE '%' || :search || '%'
                   OR CAST(rs.id AS text) ILIKE '%' || :search || '%'
                   OR EXISTS (SELECT 1 FROM device_audit_events dae2
                              WHERE dae2.device_id = rs.device_id AND dae2.tenant_id = rs.tenant_id
                                AND dae2.username ILIKE '%' || :search || '%'
                                AND dae2.event_ts <= rs.started_ts + INTERVAL '5 minutes'
                              LIMIT 1))
              AND (CAST(:minSegments AS integer) IS NULL OR rs.segment_count >= :minSegments)
              AND (CAST(:maxSegments AS integer) IS NULL OR rs.segment_count <= :maxSegments)
              AND (CAST(:minBytes AS bigint) IS NULL OR rs.total_bytes >= :minBytes)
              AND (CAST(:maxBytes AS bigint) IS NULL OR rs.total_bytes <= :maxBytes)
            ORDER BY rs.started_ts DESC
            """,
            countQuery = """
            SELECT COUNT(*)
            FROM recording_sessions rs
            LEFT JOIN devices d ON d.id = rs.device_id AND d.tenant_id = rs.tenant_id
            WHERE rs.tenant_id = :tenantId
              AND (CAST(:status AS varchar) IS NULL OR rs.status = :status)
              AND (CAST(:deviceId AS uuid) IS NULL OR rs.device_id = :deviceId)
              AND (CAST(:fromTs AS timestamptz) IS NULL OR rs.started_ts >= :fromTs)
              AND (CAST(:toTs AS timestamptz) IS NULL OR rs.started_ts <= :toTs)
              AND (CAST(:search AS varchar) IS NULL
                   OR d.hostname ILIKE '%' || :search || '%'
                   OR CAST(rs.id AS text) ILIKE '%' || :search || '%'
                   OR EXISTS (SELECT 1 FROM device_audit_events dae2
                              WHERE dae2.device_id = rs.device_id AND dae2.tenant_id = rs.tenant_id
                                AND dae2.username ILIKE '%' || :search || '%'
                                AND dae2.event_ts <= rs.started_ts + INTERVAL '5 minutes'
                              LIMIT 1))
              AND (CAST(:minSegments AS integer) IS NULL OR rs.segment_count >= :minSegments)
              AND (CAST(:maxSegments AS integer) IS NULL OR rs.segment_count <= :maxSegments)
              AND (CAST(:minBytes AS bigint) IS NULL OR rs.total_bytes >= :minBytes)
              AND (CAST(:maxBytes AS bigint) IS NULL OR rs.total_bytes <= :maxBytes)
            """,
            nativeQuery = true)
    Page<Object[]> findByTenantIdWithFiltersEnriched(
            @Param("tenantId") UUID tenantId,
            @Param("status") String status,
            @Param("deviceId") UUID deviceId,
            @Param("fromTs") Instant fromTs,
            @Param("toTs") Instant toTs,
            @Param("search") String search,
            @Param("minSegments") Integer minSegments,
            @Param("maxSegments") Integer maxSegments,
            @Param("minBytes") Long minBytes,
            @Param("maxBytes") Long maxBytes,
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
