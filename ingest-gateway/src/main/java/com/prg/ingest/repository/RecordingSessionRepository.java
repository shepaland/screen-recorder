package com.prg.ingest.repository;

import com.prg.ingest.entity.RecordingSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface RecordingSessionRepository extends JpaRepository<RecordingSession, UUID> {

    Optional<RecordingSession> findByIdAndTenantId(UUID id, UUID tenantId);

    Optional<RecordingSession> findByDeviceIdAndTenantIdAndStatus(UUID deviceId, UUID tenantId, String status);

    boolean existsByDeviceIdAndTenantIdAndStatus(UUID deviceId, UUID tenantId, String status);
}
