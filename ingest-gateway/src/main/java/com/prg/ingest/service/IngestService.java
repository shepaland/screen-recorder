package com.prg.ingest.service;

import com.prg.ingest.dto.request.ConfirmRequest;
import com.prg.ingest.dto.request.PresignRequest;
import com.prg.ingest.dto.response.ConfirmResponse;
import com.prg.ingest.dto.response.PresignResponse;
import com.prg.ingest.dto.response.SessionStatsResponse;
import com.prg.ingest.entity.RecordingSession;
import com.prg.ingest.entity.Segment;
import com.prg.ingest.exception.AccessDeniedException;
import com.prg.ingest.exception.ResourceNotFoundException;
import com.prg.ingest.exception.UploadException;
import com.prg.ingest.kafka.EventPublisher;
import com.prg.ingest.kafka.event.SegmentConfirmedEvent;
import com.prg.ingest.repository.RecordingSessionRepository;
import com.prg.ingest.repository.SegmentRepository;
import com.prg.ingest.security.DevicePrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class IngestService {

    private final SegmentRepository segmentRepository;
    private final RecordingSessionRepository sessionRepository;
    private final S3Service s3Service;
    private final EventPublisher eventPublisher;

    @Value("${kafka.confirm-mode:sync}")
    private String confirmMode;

    public ConfirmResponse confirm(ConfirmRequest request, DevicePrincipal principal) {
        if ("kafka-only".equals(confirmMode)) {
            return confirmViaKafka(request, principal);
        }
        return confirmSync(request, principal);
    }

    /**
     * Kafka-only confirm: validate segment → publish to Kafka → 202 Accepted.
     * No DB writes (segment status + session stats updated by consumer).
     * Auto-fallback to sync if Kafka publish fails.
     */
    private ConfirmResponse confirmViaKafka(ConfirmRequest request, DevicePrincipal principal) {
        Segment segment = segmentRepository.findByIdAndTenantId(
                        request.getSegmentId(), principal.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Segment not found: " + request.getSegmentId(),
                        "SEGMENT_NOT_FOUND"));

        if (principal.getDeviceId() != null && !principal.getDeviceId().equals(segment.getDeviceId())) {
            throw new AccessDeniedException(
                    "Device does not own this segment", "DEVICE_MISMATCH");
        }

        if (!request.getChecksumSha256().equals(segment.getChecksumSha256())) {
            throw new UploadException(
                    "Checksum mismatch: expected " + segment.getChecksumSha256()
                            + ", got " + request.getChecksumSha256(),
                    "CHECKSUM_MISMATCH");
        }

        try {
            eventPublisher.publish("segments.ingest",
                segment.getDeviceId().toString(),
                SegmentConfirmedEvent.builder()
                    .eventId(UUID.randomUUID())
                    .timestamp(Instant.now())
                    .tenantId(principal.getTenantId())
                    .deviceId(segment.getDeviceId())
                    .sessionId(segment.getSessionId())
                    .segmentId(segment.getId())
                    .sequenceNum(segment.getSequenceNum())
                    .s3Key(segment.getS3Key())
                    .sizeBytes(segment.getSizeBytes())
                    .durationMs(segment.getDurationMs())
                    .checksumSha256(segment.getChecksumSha256())
                    .metadata(segment.getMetadata())
                    .build());

            log.info("Kafka-only confirm: segment id={} session={} seq={}",
                    segment.getId(), segment.getSessionId(), segment.getSequenceNum());

            return ConfirmResponse.builder()
                    .segmentId(segment.getId())
                    .status("accepted")
                    .build();
        } catch (Exception e) {
            log.warn("Kafka unavailable, falling back to sync confirm: {}", e.getMessage());
            return confirmSync(request, principal);
        }
    }

    /**
     * Sync confirm: DB + S3 validation (original logic).
     */
    @Transactional
    public ConfirmResponse confirmSync(ConfirmRequest request, DevicePrincipal principal) {
        Segment segment = segmentRepository.findByIdAndTenantId(
                        request.getSegmentId(), principal.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Segment not found: " + request.getSegmentId(),
                        "SEGMENT_NOT_FOUND"));

        if (principal.getDeviceId() != null && !principal.getDeviceId().equals(segment.getDeviceId())) {
            throw new AccessDeniedException(
                    "Device does not own this segment", "DEVICE_MISMATCH");
        }

        if (!request.getChecksumSha256().equals(segment.getChecksumSha256())) {
            throw new UploadException(
                    "Checksum mismatch: expected " + segment.getChecksumSha256()
                            + ", got " + request.getChecksumSha256(),
                    "CHECKSUM_MISMATCH");
        }

        if (s3Service.objectExists(segment.getS3Key())) {
            long actualSize = s3Service.getObjectSize(segment.getS3Key());
            if (actualSize > 0 && segment.getSizeBytes() != null
                    && Math.abs(actualSize - segment.getSizeBytes()) > segment.getSizeBytes() * 0.1) {
                log.warn("Size mismatch for segment {}: expected={}, actual={}",
                        segment.getId(), segment.getSizeBytes(), actualSize);
            }
        } else {
            log.warn("S3 object not found for segment {} at key={}. "
                            + "Confirming anyway (upload may still be in progress).",
                    segment.getId(), segment.getS3Key());
        }

        segment.setStatus("confirmed");
        segmentRepository.save(segment);

        RecordingSession session = sessionRepository.findByIdAndTenantId(
                        segment.getSessionId(), principal.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Session not found: " + segment.getSessionId(),
                        "SESSION_NOT_FOUND"));

        session.setSegmentCount(session.getSegmentCount() + 1);
        session.setTotalBytes(session.getTotalBytes() + (segment.getSizeBytes() != null ? segment.getSizeBytes() : 0));
        session.setTotalDurationMs(session.getTotalDurationMs() + (segment.getDurationMs() != null ? segment.getDurationMs() : 0));
        sessionRepository.save(session);

        log.info("Confirmed segment id={} session={} seq={}", segment.getId(),
                segment.getSessionId(), segment.getSequenceNum());

        // Dual-write to Kafka (fire-and-forget, no-op when disabled)
        eventPublisher.publish("segments.ingest",
            segment.getDeviceId().toString(),
            SegmentConfirmedEvent.builder()
                .eventId(UUID.randomUUID())
                .timestamp(Instant.now())
                .tenantId(principal.getTenantId())
                .deviceId(segment.getDeviceId())
                .sessionId(segment.getSessionId())
                .segmentId(segment.getId())
                .sequenceNum(segment.getSequenceNum())
                .s3Key(segment.getS3Key())
                .sizeBytes(segment.getSizeBytes())
                .durationMs(segment.getDurationMs())
                .checksumSha256(segment.getChecksumSha256())
                .metadata(segment.getMetadata())
                .build());

        SessionStatsResponse stats = SessionStatsResponse.builder()
                .sessionId(session.getId())
                .segmentCount(session.getSegmentCount())
                .totalBytes(session.getTotalBytes())
                .totalDurationMs(session.getTotalDurationMs())
                .build();

        return ConfirmResponse.builder()
                .segmentId(segment.getId())
                .status("confirmed")
                .sessionStats(stats)
                .build();
    }

    @Transactional
    public PresignResponse presign(PresignRequest request, DevicePrincipal principal) {
        if (principal.getDeviceId() == null) {
            throw new AccessDeniedException("Device ID is required for presign", "DEVICE_ID_REQUIRED");
        }

        if (!principal.getDeviceId().equals(request.getDeviceId())) {
            throw new AccessDeniedException(
                    "Device ID mismatch: token device does not match request device",
                    "DEVICE_MISMATCH");
        }

        RecordingSession session = sessionRepository.findByIdAndTenantId(
                        request.getSessionId(), principal.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Recording session not found: " + request.getSessionId(),
                        "SESSION_NOT_FOUND"));

        // Allow late uploads to completed/interrupted sessions from the same device
        // (recovery of pending segments after server restart). 24h window.
        if (!"active".equals(session.getStatus())) {
            boolean isLateUpload = session.getDeviceId().equals(principal.getDeviceId())
                    && session.getEndedTs() != null
                    && session.getEndedTs().isAfter(java.time.Instant.now().minus(java.time.Duration.ofHours(24)));
            if (!isLateUpload) {
                throw new IllegalStateException("Session is not active, cannot upload segments");
            }
            log.info("Late upload accepted: session={} status={} seq={}", session.getId(), session.getStatus(), request.getSequenceNum());
        }

        if (!session.getDeviceId().equals(principal.getDeviceId())) {
            throw new AccessDeniedException(
                    "Device does not own this session", "DEVICE_MISMATCH");
        }

        String s3Key = buildS3Key(
                principal.getTenantId(),
                principal.getDeviceId(),
                request.getSessionId(),
                request.getSequenceNum());

        String contentType = request.getContentType() != null ? request.getContentType() : "video/mp4";

        Segment segment = Segment.builder()
                .tenantId(principal.getTenantId())
                .deviceId(principal.getDeviceId())
                .sessionId(request.getSessionId())
                .sequenceNum(request.getSequenceNum())
                .s3Bucket(s3Service.getBucket())
                .s3Key(s3Key)
                .sizeBytes(request.getSizeBytes())
                .durationMs(request.getDurationMs())
                .checksumSha256(request.getChecksumSha256())
                .status("uploaded")
                .recordedAt(request.getRecordedAt())
                .metadata(request.getMetadata())
                .build();

        segment = segmentRepository.save(segment);

        String uploadUrl = s3Service.generatePresignedPutUrl(s3Key, contentType, request.getSizeBytes());

        log.info("Generated presigned URL for segment id={} session={} seq={} key={}",
                segment.getId(), request.getSessionId(), request.getSequenceNum(), s3Key);

        return PresignResponse.builder()
                .segmentId(segment.getId())
                .uploadUrl(uploadUrl)
                .uploadMethod("PUT")
                .uploadHeaders(Map.of("Content-Type", contentType))
                .expiresInSec(s3Service.getPresignExpirySec())
                .build();
    }

    private String buildS3Key(UUID tenantId, UUID deviceId, UUID sessionId, int sequenceNum) {
        String paddedSeq = String.format("%05d", sequenceNum);
        return tenantId + "/" + deviceId + "/" + sessionId + "/" + paddedSeq + ".mp4";
    }
}
