package com.prg.ingest.controller;

import com.prg.ingest.dto.response.*;
import com.prg.ingest.dto.response.UserRecordingsResponse;
import com.prg.ingest.exception.AccessDeniedException;
import com.prg.ingest.filter.JwtValidationFilter;
import com.prg.ingest.security.DevicePrincipal;
import com.prg.ingest.service.TimelineService;
import com.prg.ingest.service.UserActivityService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ingest/users")
@RequiredArgsConstructor
@Slf4j
public class UserActivityController {

    private static final String PERMISSION_RECORDINGS_READ = "RECORDINGS:READ";

    private final UserActivityService userActivityService;
    private final TimelineService timelineService;

    /**
     * GET /api/v1/ingest/users — paginated list of users (by tenant).
     * Requires RECORDINGS:READ permission.
     */
    @GetMapping
    public ResponseEntity<UserListResponse> getUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String search,
            @RequestParam(name = "sort_by", defaultValue = "last_seen_ts") String sortBy,
            @RequestParam(name = "sort_dir", defaultValue = "desc") String sortDir,
            @RequestParam(name = "is_active", required = false) Boolean isActive,
            @RequestParam(name = "group_id", required = false) UUID groupId,
            @RequestParam(name = "ungrouped", required = false) Boolean ungrouped,
            @RequestParam(name = "tenant_id", required = false) UUID tenantIdParam,
            HttpServletRequest httpRequest) {

        DevicePrincipal principal = getPrincipalWithPermission(httpRequest, PERMISSION_RECORDINGS_READ);
        UUID tenantId = resolveEffectiveTenantId(principal, tenantIdParam);

        log.debug("Getting users list: tenant={} page={} size={} group={} ungrouped={}", tenantId, page, size, groupId, ungrouped);

        UserListResponse response = userActivityService.getUsers(
                tenantId, page, size, search, sortBy, sortDir, isActive, groupId, ungrouped);
        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/v1/ingest/users/activity?username=... — activity summary for user.
     * Username passed as query param to support DOMAIN\\user format (backslash-safe).
     */
    @GetMapping("/activity")
    public ResponseEntity<UserActivityResponse> getUserActivity(
            @RequestParam String username,
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(name = "device_id", required = false) UUID deviceId,
            @RequestParam(name = "tenant_id", required = false) UUID tenantIdParam,
            HttpServletRequest httpRequest) {

        DevicePrincipal principal = getPrincipalWithPermission(httpRequest, PERMISSION_RECORDINGS_READ);
        UUID tenantId = resolveEffectiveTenantId(principal, tenantIdParam);

        log.debug("Getting user activity: tenant={} from={} to={}", tenantId, from, to);

        UserActivityResponse response = userActivityService.getUserActivity(
                tenantId, username, from, to, deviceId);
        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/v1/ingest/users/apps?username=... — apps usage report.
     */
    @GetMapping("/apps")
    public ResponseEntity<AppsReportResponse> getUserApps(
            @RequestParam String username,
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(name = "sort_by", defaultValue = "total_duration") String sortBy,
            @RequestParam(name = "sort_dir", defaultValue = "desc") String sortDir,
            @RequestParam(name = "device_id", required = false) UUID deviceId,
            @RequestParam(name = "tenant_id", required = false) UUID tenantIdParam,
            HttpServletRequest httpRequest) {

        DevicePrincipal principal = getPrincipalWithPermission(httpRequest, PERMISSION_RECORDINGS_READ);
        UUID tenantId = resolveEffectiveTenantId(principal, tenantIdParam);

        AppsReportResponse response = userActivityService.getUserApps(
                tenantId, username, from, to, page, size, sortBy, sortDir, deviceId);
        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/v1/ingest/users/domains?username=... — domains usage report.
     */
    @GetMapping("/domains")
    public ResponseEntity<DomainsReportResponse> getUserDomains(
            @RequestParam String username,
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(name = "device_id", required = false) UUID deviceId,
            @RequestParam(name = "tenant_id", required = false) UUID tenantIdParam,
            HttpServletRequest httpRequest) {

        DevicePrincipal principal = getPrincipalWithPermission(httpRequest, PERMISSION_RECORDINGS_READ);
        UUID tenantId = resolveEffectiveTenantId(principal, tenantIdParam);

        DomainsReportResponse response = userActivityService.getUserDomains(
                tenantId, username, from, to, page, size, deviceId);
        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/v1/ingest/users/worktime?username=... — worktime report.
     */
    @GetMapping("/worktime")
    public ResponseEntity<WorktimeResponse> getUserWorktime(
            @RequestParam String username,
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(defaultValue = "Europe/Moscow") String timezone,
            @RequestParam(name = "device_id", required = false) UUID deviceId,
            @RequestParam(name = "tenant_id", required = false) UUID tenantIdParam,
            HttpServletRequest httpRequest) {

        DevicePrincipal principal = getPrincipalWithPermission(httpRequest, PERMISSION_RECORDINGS_READ);
        UUID tenantId = resolveEffectiveTenantId(principal, tenantIdParam);

        WorktimeResponse response = userActivityService.getUserWorktime(
                tenantId, username, from, to, timezone, deviceId);
        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/v1/ingest/users/timesheet?username=... — monthly timesheet.
     */
    @GetMapping("/timesheet")
    public ResponseEntity<TimesheetResponse> getUserTimesheet(
            @RequestParam String username,
            @RequestParam String month,
            @RequestParam(name = "work_start", defaultValue = "09:00") String workStart,
            @RequestParam(name = "work_end", defaultValue = "18:00") String workEnd,
            @RequestParam(defaultValue = "Europe/Moscow") String timezone,
            @RequestParam(name = "device_id", required = false) UUID deviceId,
            @RequestParam(name = "tenant_id", required = false) UUID tenantIdParam,
            HttpServletRequest httpRequest) {

        DevicePrincipal principal = getPrincipalWithPermission(httpRequest, PERMISSION_RECORDINGS_READ);
        UUID tenantId = resolveEffectiveTenantId(principal, tenantIdParam);

        TimesheetResponse response = userActivityService.getUserTimesheet(
                tenantId, username, month, workStart, workEnd, timezone, deviceId);
        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/v1/ingest/users/recordings?username=...&from=...&to=...
     * Returns recording sessions for the user based on temporal overlap with device_user_sessions.
     */
    @GetMapping("/recordings")
    public ResponseEntity<UserRecordingsResponse> getUserRecordings(
            @RequestParam String username,
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(name = "tenant_id", required = false) UUID tenantIdParam,
            HttpServletRequest httpRequest) {

        DevicePrincipal principal = getPrincipalWithPermission(httpRequest, PERMISSION_RECORDINGS_READ);
        UUID tenantId = resolveEffectiveTenantId(principal, tenantIdParam);

        UserRecordingsResponse response = userActivityService.getUserRecordings(
                tenantId, username, from, to, page, size);
        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/v1/ingest/users/timeline?date=2026-03-09&timezone=Europe/Moscow
     * Returns a full-day timeline for all users in the tenant, broken down by hour,
     * with app/site groups and recording session mapping.
     * Requires RECORDINGS:READ permission.
     */
    @GetMapping("/timeline")
    public ResponseEntity<TimelineResponse> getTimeline(
            @RequestParam String date,
            @RequestParam(defaultValue = "Europe/Moscow") String timezone,
            @RequestParam(name = "tenant_id", required = false) UUID tenantIdParam,
            HttpServletRequest httpRequest) {

        DevicePrincipal principal = getPrincipalWithPermission(httpRequest, PERMISSION_RECORDINGS_READ);
        UUID tenantId = resolveEffectiveTenantId(principal, tenantIdParam);

        log.debug("Getting timeline: tenant={} date={} tz={}", tenantId, date, timezone);

        TimelineResponse response = timelineService.getTimeline(tenantId, date, timezone);
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

    /**
     * Resolve effective tenant_id for the query.
     * Users with "global" scope (SUPER_ADMIN) must specify tenant_id explicitly.
     * Regular users always use their JWT tenant_id.
     * Security: prevents cross-tenant data exposure and DoS from querying all tenants.
     */
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
}
