package com.prg.ingest.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prg.ingest.dto.RecordingDownloadResult;
import com.prg.ingest.dto.response.*;
import com.prg.ingest.entity.Segment;
import com.prg.ingest.exception.AccessDeniedException;
import com.prg.ingest.filter.JwtValidationFilter;
import com.prg.ingest.security.DevicePrincipal;
import com.prg.ingest.service.RecordingService;
import com.prg.ingest.service.S3Service;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RestController
@RequestMapping("/api/v1/ingest/recordings")
@RequiredArgsConstructor
@Slf4j
public class RecordingController {

    private static final String PERMISSION_RECORDINGS_READ = "RECORDINGS:READ";
    private static final String PERMISSION_RECORDINGS_DELETE = "RECORDINGS:DELETE";
    private static final String PERMISSION_RECORDINGS_EXPORT = "RECORDINGS:EXPORT";

    private final RecordingService recordingService;
    private final S3Service s3Service;
    private final ObjectMapper objectMapper;

    /**
     * GET /api/v1/ingest/recordings — paginated list of recordings for the tenant.
     */
    @GetMapping
    public ResponseEntity<PageResponse<RecordingListItemResponse>> listRecordings(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(name = "device_id", required = false) UUID deviceId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String search,
            @RequestParam(name = "min_segments", required = false) Integer minSegments,
            @RequestParam(name = "max_segments", required = false) Integer maxSegments,
            @RequestParam(name = "min_bytes", required = false) Long minBytes,
            @RequestParam(name = "max_bytes", required = false) Long maxBytes,
            HttpServletRequest httpRequest) {

        DevicePrincipal principal = getPrincipalWithPermission(httpRequest, PERMISSION_RECORDINGS_READ);

        if (size < 1) size = 1;
        if (size > 100) size = 100;
        if (page < 0) page = 0;

        PageResponse<RecordingListItemResponse> response =
                recordingService.listRecordings(principal, page, size, status, deviceId, from, to,
                        search, minSegments, maxSegments, minBytes, maxBytes);

        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/v1/ingest/recordings/{id} — recording details.
     */
    @GetMapping("/{id}")
    public ResponseEntity<RecordingDetailResponse> getRecording(
            @PathVariable UUID id,
            HttpServletRequest httpRequest) {

        DevicePrincipal principal = getPrincipalWithPermission(httpRequest, PERMISSION_RECORDINGS_READ);

        log.debug("Getting recording detail: tenant={} recording={}", principal.getTenantId(), id);

        RecordingDetailResponse response = recordingService.getRecording(id, principal);
        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/v1/ingest/recordings/{id}/segments — list segments of a recording.
     */
    @GetMapping("/{id}/segments")
    public ResponseEntity<RecordingSegmentsResponse> getRecordingSegments(
            @PathVariable UUID id,
            HttpServletRequest httpRequest) {

        DevicePrincipal principal = getPrincipalWithPermission(httpRequest, PERMISSION_RECORDINGS_READ);

        log.debug("Getting recording segments: tenant={} recording={}", principal.getTenantId(), id);

        RecordingSegmentsResponse response = recordingService.getRecordingSegments(id, principal);
        return ResponseEntity.ok(response);
    }

    /**
     * DELETE /api/v1/ingest/recordings/{id} — delete a recording with all segments and S3 objects.
     * Requires RECORDINGS:DELETE permission. Active recordings cannot be deleted.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteRecording(
            @PathVariable UUID id,
            HttpServletRequest httpRequest) {

        DevicePrincipal principal = getPrincipalWithPermission(httpRequest, PERMISSION_RECORDINGS_DELETE);

        log.info("Deleting recording: tenant={} recording={}", principal.getTenantId(), id);

        recordingService.deleteRecording(id, principal);
        return ResponseEntity.noContent().build();
    }

    /**
     * GET /api/v1/ingest/recordings/{id}/download — download recording as MP4 or ZIP.
     * Requires RECORDINGS:EXPORT permission. Active recordings cannot be downloaded.
     *
     * Query param "format": auto (default), zip, mp4.
     * - auto: single segment -> MP4 stream, multiple segments -> ZIP archive
     * - zip: always ZIP archive
     * - mp4: single MP4 stream (only valid for single-segment recordings in Phase 1)
     */
    @GetMapping("/{id}/download")
    public void downloadRecording(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "auto") String format,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) throws IOException {

        DevicePrincipal principal = getPrincipalWithPermission(httpRequest, PERMISSION_RECORDINGS_EXPORT);

        log.info("Downloading recording: tenant={} recording={} format={}",
                principal.getTenantId(), id, format);

        RecordingDownloadResult download = recordingService.prepareDownload(id, format, principal);

        if (download.isUseZip()) {
            streamAsZip(download, httpResponse);
        } else {
            streamAsMp4(download, httpResponse);
        }
    }

    /**
     * GET /api/v1/ingest/recordings/by-device/{deviceId}/days
     * Returns list of recording days for a device, grouped by calendar day (in device timezone).
     */
    @GetMapping("/by-device/{deviceId}/days")
    public ResponseEntity<DeviceDaysResponse> getDeviceRecordingDays(
            @PathVariable UUID deviceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size,
            HttpServletRequest httpRequest) {

        DevicePrincipal principal = getPrincipalWithPermission(httpRequest, PERMISSION_RECORDINGS_READ);

        if (size < 1) size = 1;
        if (size > 365) size = 365;
        if (page < 0) page = 0;

        log.debug("Getting device recording days: tenant={} device={} page={} size={}",
                principal.getTenantId(), deviceId, page, size);

        DeviceDaysResponse response = recordingService.getDeviceRecordingDays(deviceId, principal, page, size);
        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/v1/ingest/recordings/by-device/{deviceId}/days/{date}/timeline
     * Returns full timeline for a specific day with sessions and segments.
     */
    @GetMapping("/by-device/{deviceId}/days/{date}/timeline")
    public ResponseEntity<DayTimelineResponse> getDeviceDayTimeline(
            @PathVariable UUID deviceId,
            @PathVariable String date,
            HttpServletRequest httpRequest) {

        DevicePrincipal principal = getPrincipalWithPermission(httpRequest, PERMISSION_RECORDINGS_READ);

        log.debug("Getting day timeline: tenant={} device={} date={}",
                principal.getTenantId(), deviceId, date);

        DayTimelineResponse response = recordingService.getDeviceDayTimeline(deviceId, date, principal);
        return ResponseEntity.ok(response);
    }

    // ---- Private helpers ----

    private void streamAsMp4(RecordingDownloadResult download,
                              HttpServletResponse response) throws IOException {
        Segment segment = download.getSegments().get(0);
        String filename = download.getBaseFilename() + ".mp4";

        response.setContentType("video/mp4");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        if (segment.getSizeBytes() != null) {
            response.setContentLengthLong(segment.getSizeBytes());
        }

        try (var s3Stream = s3Service.getObject(segment.getS3Key())) {
            s3Stream.transferTo(response.getOutputStream());
        }
    }

    private void streamAsZip(RecordingDownloadResult download,
                              HttpServletResponse response) throws IOException {
        String filename = download.getBaseFilename() + ".zip";

        response.setContentType("application/zip");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");

        try (ZipOutputStream zipOut = new ZipOutputStream(response.getOutputStream())) {
            // 1. Add recording.json metadata
            ZipEntry metaEntry = new ZipEntry(download.getBaseFilename() + "/recording.json");
            zipOut.putNextEntry(metaEntry);
            String metadataJson = buildRecordingMetadataJson(download);
            zipOut.write(metadataJson.getBytes(StandardCharsets.UTF_8));
            zipOut.closeEntry();

            // 2. Add each segment as NNNNN.mp4
            for (Segment segment : download.getSegments()) {
                String segFilename = String.format("%05d.mp4", segment.getSequenceNum());
                ZipEntry segEntry = new ZipEntry(download.getBaseFilename() + "/" + segFilename);
                if (segment.getSizeBytes() != null) {
                    segEntry.setSize(segment.getSizeBytes());
                }
                zipOut.putNextEntry(segEntry);

                try (var s3Stream = s3Service.getObject(segment.getS3Key())) {
                    s3Stream.transferTo(zipOut);
                }
                zipOut.closeEntry();
            }
        }
    }

    private String buildRecordingMetadataJson(RecordingDownloadResult download) {
        var session = download.getSession();

        List<Map<String, Object>> segmentsList = new ArrayList<>();
        for (Segment seg : download.getSegments()) {
            Map<String, Object> segMap = new LinkedHashMap<>();
            segMap.put("sequence_num", seg.getSequenceNum());
            segMap.put("filename", String.format("%05d.mp4", seg.getSequenceNum()));
            segMap.put("duration_ms", seg.getDurationMs());
            segMap.put("size_bytes", seg.getSizeBytes());
            segmentsList.add(segMap);
        }

        long totalBytes = download.getSegments().stream()
                .mapToLong(s -> s.getSizeBytes() != null ? s.getSizeBytes() : 0L)
                .sum();

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("session_id", session.getId().toString());
        meta.put("device_id", session.getDeviceId().toString());
        meta.put("device_hostname", download.getHostname());
        meta.put("started_ts", session.getStartedTs() != null ? session.getStartedTs().toString() : null);
        meta.put("ended_ts", session.getEndedTs() != null ? session.getEndedTs().toString() : null);
        meta.put("total_duration_ms", session.getTotalDurationMs());
        meta.put("total_bytes", totalBytes);
        meta.put("segment_count", download.getSegments().size());
        meta.put("segments", segmentsList);

        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(meta);
        } catch (Exception e) {
            log.error("Failed to serialize recording metadata JSON", e);
            return "{}";
        }
    }

    private DevicePrincipal getPrincipalWithPermission(HttpServletRequest request, String permission) {
        DevicePrincipal principal = (DevicePrincipal) request.getAttribute(
                JwtValidationFilter.DEVICE_PRINCIPAL_ATTRIBUTE);
        if (principal == null) {
            throw new IllegalStateException("DevicePrincipal not found in request attributes");
        }

        if (!principal.hasPermission(permission)) {
            throw new AccessDeniedException(
                    "Permission " + permission + " is required",
                    "INSUFFICIENT_PERMISSIONS");
        }

        return principal;
    }
}
