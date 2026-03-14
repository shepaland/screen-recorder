package com.prg.ingest.repository;

import com.prg.ingest.entity.Segment;
import com.prg.ingest.entity.SegmentId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SegmentRepository extends JpaRepository<Segment, SegmentId> {

    @Query(value = """
            SELECT device_id, COALESCE(SUM(size_bytes), 0) AS total_bytes, COUNT(*) AS segment_count
            FROM segments
            WHERE tenant_id = :tenantId AND device_id IN (:deviceIds)
            GROUP BY device_id
            """, nativeQuery = true)
    List<Object[]> getStorageStatsByDeviceIds(@Param("tenantId") UUID tenantId,
                                              @Param("deviceIds") List<UUID> deviceIds);

    @Query("SELECT s FROM Segment s WHERE s.id = :id AND s.tenantId = :tenantId")
    Optional<Segment> findByIdAndTenantId(@Param("id") UUID id, @Param("tenantId") UUID tenantId);

    @Query("SELECT s FROM Segment s WHERE s.sessionId = :sessionId AND s.tenantId = :tenantId ORDER BY s.sequenceNum ASC")
    List<Segment> findBySessionIdAndTenantIdOrderBySequenceNum(
            @Param("sessionId") UUID sessionId,
            @Param("tenantId") UUID tenantId);

    @Modifying
    @Query("DELETE FROM Segment s WHERE s.sessionId = :sessionId AND s.tenantId = :tenantId")
    int deleteBySessionIdAndTenantId(
            @Param("sessionId") UUID sessionId,
            @Param("tenantId") UUID tenantId);
}
