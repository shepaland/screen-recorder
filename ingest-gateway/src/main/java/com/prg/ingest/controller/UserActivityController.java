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
            HttpServletRequest httpRequest) {

        DevicePrincipal principal = getPrincipalWithPermission(httpRequest, PERMISSION_RECORDINGS_READ);

        log.debug("Getting users list: tenant={} page={} size={} search={}",
                principal.getTenantId(), page, size, search);

        UserListResponse response = userActivityService.getUsers(
                principal.getTenantId(), page, size, search, sortBy, sortDir, isActive);
        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/v1/ingest/users/{username}/activity — activity summary for user.
     */
    @GetMapping("/{username}/activity")
    public ResponseEntity<UserActivityResponse> getUserActivity(
            @PathVariable String username,
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(name = "device_id", required = false) UUID deviceId,
            HttpServletRequest httpRequest) {

        DevicePrincipal principal = getPrincipalWithPermission(httpRequest, PERMISSION_RECORDINGS_READ);

        log.debug("Getting user activity: tenant={} username={} from={} to={}",
                principal.getTenantId(), username, from, to);

        UserActivityResponse response = userActivityService.getUserActivity(
                principal.getTenantId(), username, from, to, deviceId);
        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/v1/ingest/users/{username}/apps — apps usage report.
     */
    @GetMapping("/{username}/apps")
    public ResponseEntity<AppsReportResponse> getUserApps(
            @PathVariable String username,
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(name = "sort_by", defaultValue = "total_duration") String sortBy,
            @RequestParam(name = "sort_dir", defaultValue = "desc") String sortDir,
            @RequestParam(name = "device_id", required = false) UUID deviceId,
            HttpServletRequest httpRequest) {

        DevicePrincipal principal = getPrincipalWithPermission(httpRequest, PERMISSION_RECORDINGS_READ);

        AppsReportResponse response = userActivityService.getUserApps(
                principal.getTenantId(), username, from, to, page, size, sortBy, sortDir, deviceId);
        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/v1/ingest/users/{username}/domains — domains usage report.
     */
    @GetMapping("/{username}/domains")
    public ResponseEntity<DomainsReportResponse> getUserDomains(
            @PathVariable String username,
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(name = "device_id", required = false) UUID deviceId,
            HttpServletRequest httpRequest) {

        DevicePrincipal principal = getPrincipalWithPermission(httpRequest, PERMISSION_RECORDINGS_READ);

        DomainsReportResponse response = userActivityService.getUserDomains(
                principal.getTenantId(), username, from, to, page, size, deviceId);
        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/v1/ingest/users/{username}/worktime — worktime report.
     */
    @GetMapping("/{username}/worktime")
    public ResponseEntity<WorktimeResponse> getUserWorktime(
            @PathVariable String username,
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(defaultValue = "Europe/Moscow") String timezone,
            @RequestParam(name = "device_id", required = false) UUID deviceId,
            HttpServletRequest httpRequest) {

        DevicePrincipal principal = getPrincipalWithPermission(httpRequest, PERMISSION_RECORDINGS_READ);

        WorktimeResponse response = userActivityService.getUserWorktime(
                principal.getTenantId(), username, from, to, timezone, deviceId);
        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/v1/ingest/users/{username}/timesheet — monthly timesheet.
     */
    @GetMapping("/{username}/timesheet")
    public ResponseEntity<TimesheetResponse> getUserTimesheet(
            @PathVariable String username,
            @RequestParam String month,
            @RequestParam(name = "work_start", defaultValue = "09:00") String workStart,
            @RequestParam(name = "work_end", defaultValue = "18:00") String workEnd,
            @RequestParam(defaultValue = "Europe/Moscow") String timezone,
            @RequestParam(name = "device_id", required = false) UUID deviceId,
            HttpServletRequest httpRequest) {

        DevicePrincipal principal = getPrincipalWithPermission(httpRequest, PERMISSION_RECORDINGS_READ);

        TimesheetResponse response = userActivityService.getUserTimesheet(
                principal.getTenantId(), username, month, workStart, workEnd, timezone, deviceId);
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
}
