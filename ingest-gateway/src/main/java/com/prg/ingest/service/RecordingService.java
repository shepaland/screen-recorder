package com.prg.ingest.service;

import com.prg.ingest.dto.RecordingDownloadResult;
import com.prg.ingest.dto.response.*;
import com.prg.ingest.entity.Device;
import com.prg.ingest.entity.RecordingSession;
import com.prg.ingest.entity.Segment;
import com.prg.ingest.exception.RecordingActiveException;
import com.prg.ingest.exception.ResourceNotFoundException;
import com.prg.ingest.repository.DeviceRepository;
import com.prg.ingest.repository.RecordingSessionRepository;
import com.prg.ingest.repository.SegmentRepository;
import com.prg.ingest.security.DevicePrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class RecordingService {

    private final RecordingSessionRepository sessionRepository;
    private final SegmentRepository segmentRepository;
    private final DeviceRepository deviceRepository;
    private final S3Service s3Service;

    @Transactional(readOnly = true)
    public PageResponse<RecordingListItemResponse> listRecordings(
            DevicePrincipal principal,
            int page, int size,
            String status, UUID deviceId,
            Instant from, Instant to) {

        Pageable pageable = PageRequest.of(page, size);

        Page<RecordingSession> sessionPage = sessionRepository.findByTenantIdWithFilters(
                principal.getTenantId(), status, deviceId, from, to, pageable);

        // Collect unique device IDs to resolve hostnames in a single query
        Set<UUID> deviceIds = sessionPage.getContent().stream()
                .map(RecordingSession::getDeviceId)
                .collect(Collectors.toSet());

        Map<UUID, DeviceInfo> deviceInfoMap = resolveDeviceInfo(deviceIds, principal.getTenantId());

        List<RecordingListItemResponse> content = sessionPage.getContent().stream()
                .map(session -> toListItemResponse(session, deviceInfoMap))
                .toList();

        return PageResponse.<RecordingListItemResponse>builder()
                .content(content)
                .page(sessionPage.getNumber())
                .size(sessionPage.getSize())
                .totalElements(sessionPage.getTotalElements())
                .totalPages(sessionPage.getTotalPages())
                .build();
    }

    @Transactional(readOnly = true)
    public RecordingDetailResponse getRecording(UUID recordingId, DevicePrincipal principal) {
        RecordingSession session = sessionRepository.findByIdAndTenantId(recordingId, principal.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Recording not found: " + recordingId, "RECORDING_NOT_FOUND"));

        Map<UUID, DeviceInfo> deviceInfoMap = resolveDeviceInfo(
                Set.of(session.getDeviceId()), principal.getTenantId());

        return toDetailResponse(session, deviceInfoMap);
    }

    @Transactional(readOnly = true)
    public RecordingSegmentsResponse getRecordingSegments(UUID recordingId, DevicePrincipal principal) {
        // Verify the session exists and belongs to the tenant
        RecordingSession session = sessionRepository.findByIdAndTenantId(recordingId, principal.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Recording not found: " + recordingId, "RECORDING_NOT_FOUND"));

        List<Segment> segments = segmentRepository.findBySessionIdAndTenantIdOrderBySequenceNum(
                session.getId(), principal.getTenantId());

        List<SegmentResponse> segmentResponses = segments.stream()
                .map(this::toSegmentResponse)
                .toList();

        return RecordingSegmentsResponse.builder()
                .sessionId(session.getId())
                .segments(segmentResponses)
                .build();
    }

    /**
     * Delete a recording session together with all its segments and S3 objects.
     * Active recordings cannot be deleted -- the session must be ended first.
     *
     * Deletion order: S3 objects -> segments rows -> recording_sessions row.
     */
    @Transactional
    public void deleteRecording(UUID recordingId, DevicePrincipal principal) {
        // 1. Find the recording, enforce tenant isolation
        RecordingSession session = sessionRepository.findByIdAndTenantId(recordingId, principal.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Recording not found: " + recordingId, "RECORDING_NOT_FOUND"));

        // 2. Reject deletion of active recordings
        if ("active".equals(session.getStatus())) {
            throw new RecordingActiveException(
                    "Cannot delete an active recording. End the session first.");
        }

        // 3. Collect all segments
        List<Segment> segments = segmentRepository.findBySessionIdAndTenantIdOrderBySequenceNum(
                session.getId(), principal.getTenantId());

        // 4. Delete objects from S3 (batch)
        List<String> s3Keys = segments.stream()
                .map(Segment::getS3Key)
                .filter(key -> key != null && !key.isEmpty())
                .toList();

        if (!s3Keys.isEmpty()) {
            List<String> failedKeys = s3Service.deleteObjects(s3Keys);
            if (!failedKeys.isEmpty()) {
                log.warn("Failed to delete {} S3 objects for recording {}. Proceeding with DB cleanup.",
                        failedKeys.size(), recordingId);
            }
        }

        // 5. Delete segment rows
        int deletedSegments = segmentRepository.deleteBySessionIdAndTenantId(
                session.getId(), principal.getTenantId());

        // 6. Delete the session row
        sessionRepository.delete(session);

        // 7. Audit via structured log
        long totalBytes = segments.stream()
                .mapToLong(s -> s.getSizeBytes() != null ? s.getSizeBytes() : 0L)
                .sum();
        log.info("RECORDING_DELETED: id={}, device_id={}, segments_deleted={}, " +
                        "s3_objects_deleted={}, total_bytes={}, tenant_id={}",
                recordingId, session.getDeviceId(), deletedSegments,
                s3Keys.size(), totalBytes, principal.getTenantId());
    }

    /**
     * Prepare download metadata for a recording.
     * Returns information needed by the controller to stream the response
     * as either a single MP4 or a ZIP archive.
     */
    @Transactional(readOnly = true)
    public RecordingDownloadResult prepareDownload(UUID recordingId, String format,
                                                    DevicePrincipal principal) {
        // 1. Find the recording, enforce tenant isolation
        RecordingSession session = sessionRepository.findByIdAndTenantId(recordingId, principal.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Recording not found: " + recordingId, "RECORDING_NOT_FOUND"));

        // 2. Reject download of active recordings
        if ("active".equals(session.getStatus())) {
            throw new RecordingActiveException(
                    "Cannot download an active recording. End the session first.");
        }

        // 3. Load segments, keep only confirmed/indexed
        List<Segment> segments = segmentRepository.findBySessionIdAndTenantIdOrderBySequenceNum(
                session.getId(), principal.getTenantId());

        List<Segment> confirmedSegments = segments.stream()
                .filter(s -> "confirmed".equals(s.getStatus()) || "indexed".equals(s.getStatus()))
                .toList();

        if (confirmedSegments.isEmpty()) {
            throw new ResourceNotFoundException(
                    "No confirmed segments in this recording", "RECORDING_NO_SEGMENTS");
        }

        // 4. Resolve device hostname for the filename
        String hostname = deviceRepository.findByIdAndTenantId(session.getDeviceId(), principal.getTenantId())
                .map(Device::getHostname)
                .orElse("unknown");

        // 5. Build base filename
        String baseFilename = buildDownloadFilename(session, hostname);

        // 6. Decide on format
        boolean useZip;
        if ("zip".equals(format)) {
            useZip = true;
        } else if ("mp4".equals(format)) {
            useZip = false;
        } else {
            // "auto": single segment -> mp4, multiple -> zip
            useZip = confirmedSegments.size() > 1;
        }

        return RecordingDownloadResult.builder()
                .session(session)
                .segments(confirmedSegments)
                .hostname(hostname)
                .baseFilename(baseFilename)
                .useZip(useZip)
                .build();
    }

    private String buildDownloadFilename(RecordingSession session, String hostname) {
        String ts = session.getStartedTs().toString()
                .replace(":", "-")
                .replace("T", "_");
        // Trim to date_time portion (2026-03-05_10-00-00)
        if (ts.length() > 19) {
            ts = ts.substring(0, 19);
        }
        String shortId = session.getId().toString().substring(0, 8);
        String safeHostname = sanitizeForFilename(hostname);
        return "recording_" + shortId + "_" + safeHostname + "_" + ts;
    }

    private String sanitizeForFilename(String input) {
        if (input == null || input.isBlank()) return "unknown";
        return input.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private record DeviceInfo(String hostname, boolean deleted) {}

    private Map<UUID, DeviceInfo> resolveDeviceInfo(Set<UUID> deviceIds, UUID tenantId) {
        if (deviceIds.isEmpty()) {
            return Map.of();
        }
        List<Device> devices = deviceRepository.findByIdInAndTenantId(deviceIds, tenantId);
        return devices.stream()
                .collect(Collectors.toMap(
                        Device::getId,
                        d -> new DeviceInfo(d.getHostname(), Boolean.TRUE.equals(d.getIsDeleted())),
                        (a, b) -> a));
    }

    private RecordingListItemResponse toListItemResponse(RecordingSession session, Map<UUID, DeviceInfo> deviceInfoMap) {
        DeviceInfo info = deviceInfoMap.get(session.getDeviceId());
        return RecordingListItemResponse.builder()
                .id(session.getId())
                .deviceId(session.getDeviceId())
                .deviceHostname(info != null ? info.hostname() : null)
                .deviceDeleted(info != null && info.deleted())
                .status(session.getStatus())
                .startedTs(session.getStartedTs())
                .endedTs(session.getEndedTs())
                .segmentCount(session.getSegmentCount())
                .totalBytes(session.getTotalBytes())
                .totalDurationMs(session.getTotalDurationMs())
                .metadata(session.getMetadata())
                .build();
    }

    private RecordingDetailResponse toDetailResponse(RecordingSession session, Map<UUID, DeviceInfo> deviceInfoMap) {
        DeviceInfo info = deviceInfoMap.get(session.getDeviceId());
        return RecordingDetailResponse.builder()
                .id(session.getId())
                .tenantId(session.getTenantId())
                .deviceId(session.getDeviceId())
                .deviceHostname(info != null ? info.hostname() : null)
                .deviceDeleted(info != null && info.deleted())
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

    private SegmentResponse toSegmentResponse(Segment segment) {
        return SegmentResponse.builder()
                .id(segment.getId())
                .sequenceNum(segment.getSequenceNum())
                .durationMs(segment.getDurationMs())
                .sizeBytes(segment.getSizeBytes())
                .status(segment.getStatus())
                .s3Key(segment.getS3Key())
                .build();
    }
}
