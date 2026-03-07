package com.prg.ingest.controller;

import com.prg.ingest.dto.request.SubmitAuditEventsRequest;
import com.prg.ingest.dto.response.DeviceAuditEventsResponse;
import com.prg.ingest.dto.response.SubmitAuditEventsResponse;
import com.prg.ingest.exception.AccessDeniedException;
import com.prg.ingest.filter.JwtValidationFilter;
import com.prg.ingest.security.DevicePrincipal;
import com.prg.ingest.service.AuditEventService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ingest/audit-events")
@RequiredArgsConstructor
@Slf4j
public class AuditEventController {

    private static final String PERMISSION_RECORDINGS_READ = "RECORDINGS:READ";

    private final AuditEventService auditEventService;

    /**
     * POST /api/v1/ingest/audit-events — submit batch of audit events from device agent.
     * Authenticated via device JWT. No specific permission required (device submits own events).
     */
    @PostMapping
    public ResponseEntity<SubmitAuditEventsResponse> submitEvents(
            @Valid @RequestBody SubmitAuditEventsRequest request,
            HttpServletRequest httpRequest) {

        DevicePrincipal principal = getPrincipal(httpRequest);
        UUID correlationId = extractCorrelationId(httpRequest);

        log.debug("Submitting audit events: device={} count={} tenant={}",
                request.getDeviceId(), request.getEvents().size(), principal.getTenantId());

        SubmitAuditEventsResponse response = auditEventService.submitEvents(request, principal, correlationId);
        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/v1/ingest/audit-events — get audit events for device playback UI.
     * Requires RECORDINGS:READ permission.
     */
    @GetMapping
    public ResponseEntity<DeviceAuditEventsResponse> getEvents(
            @RequestParam("device_id") UUID deviceId,
            @RequestParam String date,
            @RequestParam(name = "event_type", required = false) String eventType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "500") int size,
            HttpServletRequest httpRequest) {

        DevicePrincipal principal = getPrincipalWithPermission(httpRequest, PERMISSION_RECORDINGS_READ);

        if (size < 1) size = 1;
        if (size > 1000) size = 1000;
        if (page < 0) page = 0;

        log.debug("Getting audit events: device={} date={} tenant={}",
                deviceId, date, principal.getTenantId());

        DeviceAuditEventsResponse response = auditEventService.getEvents(
                deviceId, date, eventType, page, size, principal);
        return ResponseEntity.ok(response);
    }

    private DevicePrincipal getPrincipal(HttpServletRequest request) {
        DevicePrincipal principal = (DevicePrincipal) request.getAttribute(
                JwtValidationFilter.DEVICE_PRINCIPAL_ATTRIBUTE);
        if (principal == null) {
            throw new IllegalStateException("DevicePrincipal not found in request attributes");
        }
        return principal;
    }

    private DevicePrincipal getPrincipalWithPermission(HttpServletRequest request, String permission) {
        DevicePrincipal principal = getPrincipal(request);
        if (!principal.hasPermission(permission)) {
            throw new AccessDeniedException(
                    "Permission " + permission + " is required",
                    "INSUFFICIENT_PERMISSIONS");
        }
        return principal;
    }

    private UUID extractCorrelationId(HttpServletRequest request) {
        String header = request.getHeader("X-Correlation-ID");
        if (header != null) {
            try {
                return UUID.fromString(header);
            } catch (IllegalArgumentException e) {
                // ignore
            }
        }
        return UUID.randomUUID();
    }
}
