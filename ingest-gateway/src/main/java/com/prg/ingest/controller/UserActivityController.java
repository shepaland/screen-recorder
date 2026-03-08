package com.prg.ingest.controller;

import com.prg.ingest.dto.response.*;
import com.prg.ingest.exception.AccessDeniedException;
import com.prg.ingest.filter.JwtValidationFilter;
import com.prg.ingest.security.DevicePrincipal;
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
            @RequestParam(name = "tenant_id", required = false) UUID tenantIdParam,
            HttpServletRequest httpRequest) {

        DevicePrincipal principal = getPrincipalWithPermission(httpRequest, PERMISSION_RECORDINGS_READ);
        UUID tenantId = resolveEffectiveTenantId(principal, tenantIdParam);

        log.debug("Getting users list: tenant={} page={} size={}", tenantId, page, size);

        UserListResponse response = userActivityService.getUsers(
                tenantId, page, size, search, sortBy, sortDir, isActive);
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
     * Users with "global" scope (SUPER_ADMIN) can specify tenant_id as query param,
     * or omit it to query all tenants (null = no tenant filter).
     * Regular users always use their JWT tenant_id.
     */
    private UUID resolveEffectiveTenantId(DevicePrincipal principal, UUID tenantIdParam) {
        if (principal.hasScope("global")) {
            return tenantIdParam; // null = all tenants, or specific tenant if provided
        }
        return principal.getTenantId();
    }
}
