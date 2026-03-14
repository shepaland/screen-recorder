package com.prg.ingest.controller;

import com.prg.ingest.dto.request.SubmitInputEventsRequest;
import com.prg.ingest.dto.response.InputEventResponse;
import com.prg.ingest.dto.response.InputEventsResponse;
import com.prg.ingest.exception.AccessDeniedException;
import com.prg.ingest.filter.JwtValidationFilter;
import com.prg.ingest.security.DevicePrincipal;
import com.prg.ingest.service.InputEventService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ingest/activity")
@RequiredArgsConstructor
@Slf4j
public class InputEventController {

    private final InputEventService inputEventService;

    private static final Set<String> BEHAVIOR_AUDIT_ROLES = Set.of(
            "SUPER_ADMIN", "OWNER", "TENANT_ADMIN");

    /**
     * POST /api/v1/ingest/activity/input-events
     * Submit batch of input events from device agent.
     * Requires device JWT with device_id.
     */
    @PostMapping("/input-events")
    public ResponseEntity<InputEventsResponse> submitInputEvents(
            @Valid @RequestBody SubmitInputEventsRequest request,
            HttpServletRequest httpRequest) {

        DevicePrincipal principal = getPrincipal(httpRequest);

        if (principal.getDeviceId() == null) {
            throw new AccessDeniedException("device_id is required in JWT for this endpoint", "DEVICE_ID_REQUIRED");
        }

        if (!principal.getDeviceId().equals(request.getDeviceId())) {
            throw new AccessDeniedException("device_id mismatch with JWT claims", "DEVICE_MISMATCH");
        }

        UUID correlationId = extractCorrelationId(httpRequest);

        log.debug("Submitting input events: device={} username={} tenant={}",
                request.getDeviceId(), request.getUsername(), principal.getTenantId());

        InputEventsResponse response = inputEventService.saveInputEvents(request, principal, correlationId);
        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/v1/ingest/activity/input-events
     * Query input events for behavior audit UI.
     * Requires user JWT with roles: SUPER_ADMIN, OWNER, or TENANT_ADMIN.
     */
    @GetMapping("/input-events")
    public ResponseEntity<Page<InputEventResponse>> queryInputEvents(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(name = "event_type", required = false) String eventType,
            @RequestParam(required = false) String username,
            @RequestParam(name = "device_id", required = false) UUID deviceId,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(name = "sort_by", defaultValue = "event_ts") String sortBy,
            @RequestParam(name = "sort_dir", defaultValue = "desc") String sortDir,
            @RequestParam(name = "tenant_id", required = false) UUID tenantIdParam,
            HttpServletRequest httpRequest) {

        DevicePrincipal principal = getPrincipalWithRole(httpRequest);
        UUID tenantId = resolveEffectiveTenantId(principal, tenantIdParam);

        // Validate date range (max 31 days)
        long daysBetween = java.time.Duration.between(from, to).toDays();
        if (daysBetween > 31) {
            throw new IllegalArgumentException("Date range must not exceed 31 days");
        }

        List<String> eventTypes = null;
        if (eventType != null && !eventType.isBlank()) {
            eventTypes = Arrays.asList(eventType.split(","));
        }

        log.debug("Querying input events: tenant={} from={} to={} types={}", tenantId, from, to, eventTypes);

        Page<InputEventResponse> result = inputEventService.queryInputEvents(
                tenantId, from, to, eventTypes, username, deviceId, search, page, size, sortBy, sortDir);

        return ResponseEntity.ok(result);
    }

    private DevicePrincipal getPrincipal(HttpServletRequest request) {
        DevicePrincipal principal = (DevicePrincipal) request.getAttribute(
                JwtValidationFilter.DEVICE_PRINCIPAL_ATTRIBUTE);
        if (principal == null) {
            throw new IllegalStateException("DevicePrincipal not found in request attributes");
        }
        return principal;
    }

    private DevicePrincipal getPrincipalWithRole(HttpServletRequest request) {
        DevicePrincipal principal = getPrincipal(request);
        boolean hasRole = principal.getRoles().stream()
                .anyMatch(BEHAVIOR_AUDIT_ROLES::contains);
        if (!hasRole) {
            throw new AccessDeniedException(
                    "One of roles [SUPER_ADMIN, OWNER, TENANT_ADMIN] is required",
                    "INSUFFICIENT_ROLE");
        }
        return principal;
    }

    private UUID resolveEffectiveTenantId(DevicePrincipal principal, UUID tenantIdParam) {
        if (principal.hasScope("global")) {
            if (tenantIdParam == null) {
                throw new IllegalArgumentException(
                        "tenant_id is required for global scope. Specify tenant_id query parameter.");
            }
            return tenantIdParam;
        }
        return principal.getTenantId();
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
