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
import jakarta.persistence.EntityManager;
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
    private final EntityManager entityManager;

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

    @Transactional(readOnly = true)
    public DeviceDaysResponse getDeviceRecordingDays(UUID deviceId, DevicePrincipal principal, int page, int size) {
        // 1. Find device, get timezone
        Device device = deviceRepository.findByIdAndTenantId(deviceId, principal.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Device not found: " + deviceId, "DEVICE_NOT_FOUND"));

        String timezone = device.getTimezone() != null ? device.getTimezone() : "Europe/Moscow";

        int queryOffset = page * size;

        @SuppressWarnings("unchecked")
        List<Object[]> dayRows = entityManager.createNativeQuery("""
                SELECT
                    DATE(rs.started_ts AT TIME ZONE :tz) as recording_date,
                    COUNT(DISTINCT rs.id) as session_count,
                    COALESCE(SUM(rs.segment_count), 0) as segment_count,
                    COALESCE(SUM(rs.total_bytes), 0) as total_bytes,
                    COALESCE(SUM(rs.total_duration_ms), 0) as total_duration_ms,
                    BOOL_OR(rs.status = 'active') as is_live,
                    MIN(rs.started_ts) as first_started_ts,
                    MAX(COALESCE(rs.ended_ts, rs.updated_ts)) as last_ended_ts
                FROM recording_sessions rs
                WHERE rs.device_id = :deviceId
                  AND rs.tenant_id = :tenantId
                GROUP BY 1
                ORDER BY 1 DESC
                LIMIT :lim OFFSET :off
                """)
                .setParameter("deviceId", deviceId)
                .setParameter("tenantId", principal.getTenantId())
                .setParameter("tz", timezone)
                .setParameter("lim", size)
                .setParameter("off", queryOffset)
                .getResultList();

        long totalElements = ((Number) entityManager.createNativeQuery("""
                SELECT COUNT(DISTINCT DATE(rs.started_ts AT TIME ZONE :tz))
                FROM recording_sessions rs
                WHERE rs.device_id = :deviceId
                  AND rs.tenant_id = :tenantId
                """)
                .setParameter("deviceId", deviceId)
                .setParameter("tenantId", principal.getTenantId())
                .setParameter("tz", timezone)
                .getSingleResult()).longValue();

        List<RecordingDayResponse> days = dayRows.stream()
                .map(row -> RecordingDayResponse.builder()
                        .date(row[0].toString())
                        .sessionCount(((Number) row[1]).intValue())
                        .segmentCount(((Number) row[2]).intValue())
                        .totalBytes(((Number) row[3]).longValue())
                        .totalDurationMs(((Number) row[4]).longValue())
                        .live((Boolean) row[5])
                        .firstStartedTs(toInstant(row[6]))
                        .lastEndedTs(toInstant(row[7]))
                        .build())
                .toList();

        int totalPages = (int) Math.ceil((double) totalElements / size);

        return DeviceDaysResponse.builder()
                .deviceId(deviceId)
                .deviceHostname(device.getHostname())
                .timezone(timezone)
                .days(days)
                .page(page)
                .size(size)
                .totalElements(totalElements)
                .totalPages(totalPages)
                .build();
    }

    @Transactional(readOnly = true)
    public DayTimelineResponse getDeviceDayTimeline(UUID deviceId, String date, DevicePrincipal principal) {
        // 1. Find device, get timezone
        Device device = deviceRepository.findByIdAndTenantId(deviceId, principal.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Device not found: " + deviceId, "DEVICE_NOT_FOUND"));

        String timezone = device.getTimezone() != null ? device.getTimezone() : "Europe/Moscow";

        // 2. Get all sessions for this day
        List<RecordingSession> sessions = sessionRepository.findSessionsByDeviceIdAndDate(
                deviceId, principal.getTenantId(), timezone, date);

        if (sessions.isEmpty()) {
            throw new ResourceNotFoundException(
                    "No recordings found for device " + deviceId + " on " + date,
                    "NO_RECORDINGS_FOR_DATE");
        }

        // 3. Build timeline with segments
        List<TimelineSessionResponse> sessionResponses = sessions.stream()
                .map(session -> {
                    List<Segment> segments = segmentRepository
                            .findBySessionIdAndTenantIdOrderBySequenceNum(session.getId(), principal.getTenantId());

                    List<TimelineSegmentResponse> segmentResponses = segments.stream()
                            .map(seg -> TimelineSegmentResponse.builder()
                                    .id(seg.getId())
                                    .sequenceNum(seg.getSequenceNum())
                                    .durationMs(seg.getDurationMs() != null ? seg.getDurationMs() : 0L)
                                    .sizeBytes(seg.getSizeBytes() != null ? seg.getSizeBytes() : 0L)
                                    .status(seg.getStatus())
                                    .s3Key(seg.getS3Key())
                                    .recordedAt(seg.getRecordedAt())
                                    .build())
                            .toList();

                    return TimelineSessionResponse.builder()
                            .sessionId(session.getId())
                            .status(session.getStatus())
                            .startedTs(session.getStartedTs())
                            .endedTs(session.getEndedTs())
                            .segmentCount(session.getSegmentCount())
                            .totalDurationMs(session.getTotalDurationMs())
                            .totalBytes(session.getTotalBytes())
                            .segments(segmentResponses)
                            .build();
                })
                .toList();

        return DayTimelineResponse.builder()
                .deviceId(deviceId)
                .date(date)
                .timezone(timezone)
                .sessions(sessionResponses)
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

    /**
     * Safely convert a native query timestamp column to Instant.
     * Hibernate 6 + PostgreSQL timestamptz returns java.time.Instant directly,
     * but older drivers may return java.sql.Timestamp.
     */
    private static Instant toInstant(Object value) {
        if (value == null) return null;
        if (value instanceof Instant inst) return inst;
        if (value instanceof java.sql.Timestamp ts) return ts.toInstant();
        if (value instanceof java.time.OffsetDateTime odt) return odt.toInstant();
        return Instant.parse(value.toString());
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
