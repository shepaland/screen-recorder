package com.prg.playback.repository;

import com.prg.playback.entity.RecordingSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface RecordingSessionRepository extends JpaRepository<RecordingSession, UUID> {
    Optional<RecordingSession> findByIdAndTenantId(UUID id, UUID tenantId);
}
