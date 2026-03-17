package com.prg.playback.repository;

import com.prg.playback.entity.Segment;
import com.prg.playback.entity.SegmentId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SegmentRepository extends JpaRepository<Segment, SegmentId> {

    @Query("SELECT s FROM Segment s WHERE s.id = :id AND s.tenantId = :tenantId")
    Optional<Segment> findByIdAndTenantId(@Param("id") UUID id, @Param("tenantId") UUID tenantId);

    @Query("SELECT s FROM Segment s WHERE s.sessionId = :sessionId AND s.tenantId = :tenantId AND s.status = 'confirmed' ORDER BY s.sequenceNum ASC")
    List<Segment> findConfirmedBySessionIdAndTenantId(
            @Param("sessionId") UUID sessionId, @Param("tenantId") UUID tenantId);
}
