package com.prg.ingest.service;

import com.prg.ingest.dto.response.*;
import com.prg.ingest.entity.Device;
import com.prg.ingest.entity.RecordingSession;
import com.prg.ingest.entity.Segment;
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

        Map<UUID, String> deviceHostnames = resolveDeviceHostnames(deviceIds, principal.getTenantId());

        List<RecordingListItemResponse> content = sessionPage.getContent().stream()
                .map(session -> toListItemResponse(session, deviceHostnames))
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

        Map<UUID, String> deviceHostnames = resolveDeviceHostnames(
                Set.of(session.getDeviceId()), principal.getTenantId());

        return toDetailResponse(session, deviceHostnames);
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

    private Map<UUID, String> resolveDeviceHostnames(Set<UUID> deviceIds, UUID tenantId) {
        if (deviceIds.isEmpty()) {
            return Map.of();
        }
        List<Device> devices = deviceRepository.findByIdInAndTenantId(deviceIds, tenantId);
        return devices.stream()
                .collect(Collectors.toMap(Device::getId, Device::getHostname, (a, b) -> a));
    }

    private RecordingListItemResponse toListItemResponse(RecordingSession session, Map<UUID, String> hostnames) {
        return RecordingListItemResponse.builder()
                .id(session.getId())
                .deviceId(session.getDeviceId())
                .deviceHostname(hostnames.getOrDefault(session.getDeviceId(), null))
                .status(session.getStatus())
                .startedTs(session.getStartedTs())
                .endedTs(session.getEndedTs())
                .segmentCount(session.getSegmentCount())
                .totalBytes(session.getTotalBytes())
                .totalDurationMs(session.getTotalDurationMs())
                .metadata(session.getMetadata())
                .build();
    }

    private RecordingDetailResponse toDetailResponse(RecordingSession session, Map<UUID, String> hostnames) {
        return RecordingDetailResponse.builder()
                .id(session.getId())
                .tenantId(session.getTenantId())
                .deviceId(session.getDeviceId())
                .deviceHostname(hostnames.getOrDefault(session.getDeviceId(), null))
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
