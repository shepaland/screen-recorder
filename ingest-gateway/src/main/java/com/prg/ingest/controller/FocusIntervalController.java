package com.prg.ingest.controller;

import com.prg.ingest.dto.request.SubmitFocusIntervalsRequest;
import com.prg.ingest.dto.response.FocusIntervalsResponse;
import com.prg.ingest.exception.AccessDeniedException;
import com.prg.ingest.filter.JwtValidationFilter;
import com.prg.ingest.security.DevicePrincipal;
import com.prg.ingest.service.FocusIntervalService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ingest/activity")
@RequiredArgsConstructor
@Slf4j
public class FocusIntervalController {

    private final FocusIntervalService focusIntervalService;

    /**
     * POST /api/v1/ingest/activity/focus-intervals
     * Submit batch of focus intervals from device agent.
     * Requires device JWT with device_id.
     */
    @PostMapping("/focus-intervals")
    public ResponseEntity<FocusIntervalsResponse> submitFocusIntervals(
            @Valid @RequestBody SubmitFocusIntervalsRequest request,
            HttpServletRequest httpRequest) {

        DevicePrincipal principal = getPrincipal(httpRequest);

        // Mandatory: device_id must be present in JWT
        if (principal.getDeviceId() == null) {
            throw new AccessDeniedException("device_id is required in JWT for this endpoint", "DEVICE_ID_REQUIRED");
        }

        // Validate device_id matches
        if (!principal.getDeviceId().equals(request.getDeviceId())) {
            throw new AccessDeniedException("device_id mismatch with JWT claims", "DEVICE_MISMATCH");
        }

        UUID correlationId = extractCorrelationId(httpRequest);

        log.debug("Submitting focus intervals: device={} username={} count={} tenant={}",
                request.getDeviceId(), request.getUsername(),
                request.getIntervals().size(), principal.getTenantId());

        FocusIntervalsResponse response = focusIntervalService.submitIntervals(request, principal, correlationId);
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
