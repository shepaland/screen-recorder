package com.prg.ingest.controller;

import com.prg.ingest.dto.response.PageResponse;
import com.prg.ingest.dto.response.RecordingDetailResponse;
import com.prg.ingest.dto.response.RecordingListItemResponse;
import com.prg.ingest.dto.response.RecordingSegmentsResponse;
import com.prg.ingest.exception.AccessDeniedException;
import com.prg.ingest.filter.JwtValidationFilter;
import com.prg.ingest.security.DevicePrincipal;
import com.prg.ingest.service.RecordingService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ingest/recordings")
@RequiredArgsConstructor
@Slf4j
public class RecordingController {

    private static final String PERMISSION_RECORDINGS_READ = "RECORDINGS:READ";

    private final RecordingService recordingService;

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
            HttpServletRequest httpRequest) {

        DevicePrincipal principal = getPrincipalWithPermission(httpRequest);

        if (size < 1) size = 1;
        if (size > 100) size = 100;
        if (page < 0) page = 0;

        log.debug("Listing recordings: tenant={} page={} size={} status={} device_id={} from={} to={}",
                principal.getTenantId(), page, size, status, deviceId, from, to);

        PageResponse<RecordingListItemResponse> response =
                recordingService.listRecordings(principal, page, size, status, deviceId, from, to);

        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/v1/ingest/recordings/{id} — recording details.
     */
    @GetMapping("/{id}")
    public ResponseEntity<RecordingDetailResponse> getRecording(
            @PathVariable UUID id,
            HttpServletRequest httpRequest) {

        DevicePrincipal principal = getPrincipalWithPermission(httpRequest);

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

        DevicePrincipal principal = getPrincipalWithPermission(httpRequest);

        log.debug("Getting recording segments: tenant={} recording={}", principal.getTenantId(), id);

        RecordingSegmentsResponse response = recordingService.getRecordingSegments(id, principal);
        return ResponseEntity.ok(response);
    }

    private DevicePrincipal getPrincipalWithPermission(HttpServletRequest request) {
        DevicePrincipal principal = (DevicePrincipal) request.getAttribute(
                JwtValidationFilter.DEVICE_PRINCIPAL_ATTRIBUTE);
        if (principal == null) {
            throw new IllegalStateException("DevicePrincipal not found in request attributes");
        }

        if (!principal.hasPermission(PERMISSION_RECORDINGS_READ)) {
            throw new AccessDeniedException(
                    "Permission " + PERMISSION_RECORDINGS_READ + " is required",
                    "INSUFFICIENT_PERMISSIONS");
        }

        return principal;
    }
}
