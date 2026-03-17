package com.prg.ingest.service;

import com.prg.ingest.dto.request.CreateSessionRequest;
import com.prg.ingest.dto.response.SessionResponse;
import com.prg.ingest.entity.RecordingSession;
import com.prg.ingest.exception.AccessDeniedException;
import com.prg.ingest.exception.ResourceNotFoundException;
import com.prg.ingest.repository.RecordingSessionRepository;
import com.prg.ingest.security.DevicePrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class SessionService {

    private final RecordingSessionRepository sessionRepository;

    @Transactional
    public SessionResponse createSession(CreateSessionRequest request, DevicePrincipal principal) {
        if (principal.getDeviceId() == null) {
            throw new AccessDeniedException("Device ID is required for session creation", "DEVICE_ID_REQUIRED");
        }

        if (!principal.getDeviceId().equals(request.getDeviceId())) {
            throw new AccessDeniedException(
                    "Device ID mismatch: token device does not match request device",
                    "DEVICE_MISMATCH");
        }

        // Auto-close stale active session if exists (agent restart recovery)
        sessionRepository.findByDeviceIdAndTenantIdAndStatus(
                principal.getDeviceId(), principal.getTenantId(), "active")
                .ifPresent(staleSession -> {
                    log.warn("Auto-closing stale active session id={} for device={} (started at {})",
                            staleSession.getId(), staleSession.getDeviceId(), staleSession.getStartedTs());
                    staleSession.setStatus("interrupted");
                    staleSession.setEndedTs(Instant.now());
                    sessionRepository.save(staleSession);
                });

        RecordingSession session = RecordingSession.builder()
                .tenantId(principal.getTenantId())
                .deviceId(principal.getDeviceId())
                .userId(principal.getUserId())
                .status("active")
                .startedTs(Instant.now())
                .metadata(request.getMetadata())
                .build();

        session = sessionRepository.saveAndFlush(session);

        log.info("Created recording session id={} for device={} tenant={}",
                session.getId(), session.getDeviceId(), session.getTenantId());

        return toSessionResponse(session);
    }

    @Transactional
    public SessionResponse endSession(UUID sessionId, DevicePrincipal principal) {
        RecordingSession session = sessionRepository.findByIdAndTenantId(sessionId, principal.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Recording session not found: " + sessionId, "SESSION_NOT_FOUND"));

        if (principal.getDeviceId() != null && !principal.getDeviceId().equals(session.getDeviceId())) {
            throw new AccessDeniedException(
                    "Device does not own this session", "DEVICE_MISMATCH");
        }

        if (!"active".equals(session.getStatus())) {
            throw new IllegalStateException(
                    "Session is already " + session.getStatus() + ", cannot end");
        }

        session.setStatus("completed");
        session.setEndedTs(Instant.now());
        session = sessionRepository.save(session);

        log.info("Ended recording session id={} device={} segments={} bytes={}",
                session.getId(), session.getDeviceId(),
                session.getSegmentCount(), session.getTotalBytes());

        return toSessionResponse(session);
    }

    @Transactional(readOnly = true)
    public SessionResponse getSession(UUID sessionId, DevicePrincipal principal) {
        RecordingSession session = sessionRepository.findByIdAndTenantId(sessionId, principal.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Recording session not found: " + sessionId, "SESSION_NOT_FOUND"));

        if (principal.getDeviceId() != null && !principal.getDeviceId().equals(session.getDeviceId())) {
            if (!principal.hasPermission("RECORDINGS:READ")) {
                throw new AccessDeniedException(
                        "No permission to view this session", "ACCESS_DENIED");
            }
        }

        return toSessionResponse(session);
    }

    private SessionResponse toSessionResponse(RecordingSession session) {
        return SessionResponse.builder()
                .id(session.getId())
                .tenantId(session.getTenantId())
                .deviceId(session.getDeviceId())
                .userId(session.getUserId())
                .status(session.getStatus())
                .startedTs(session.getStartedTs())
                .endedTs(session.getEndedTs())
                .segmentCount(session.getSegmentCount())
                .totalBytes(session.getTotalBytes())
                .totalDurationMs(session.getTotalDurationMs())
                .metadata(session.getMetadata())
                .createdTs(session.getCreatedTs())
                .updatedTs(session.getUpdatedTs())
                .build();
    }
}
