package com.prg.ingest.controller;

import com.prg.ingest.dto.catalog.*;
import com.prg.ingest.entity.catalog.AppGroup.GroupType;
import com.prg.ingest.entity.catalog.AppGroupItem.ItemType;
import com.prg.ingest.exception.AccessDeniedException;
import com.prg.ingest.filter.JwtValidationFilter;
import com.prg.ingest.security.DevicePrincipal;
import com.prg.ingest.service.catalog.DashboardService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ingest/dashboard")
@RequiredArgsConstructor
@Slf4j
public class DashboardController {

    private static final String PERMISSION_CATALOGS_READ = "CATALOGS:READ";

    private final DashboardService dashboardService;

    @GetMapping("/metrics")
    public ResponseEntity<DashboardMetricsResponse> getMetrics(
            @RequestParam(name = "tenant_id", required = false) UUID tenantIdParam,
            HttpServletRequest httpRequest) {

        DevicePrincipal principal = getPrincipalWithPermission(httpRequest, PERMISSION_CATALOGS_READ);
        UUID tenantId = resolveEffectiveTenantId(principal, tenantIdParam);

        log.debug("Getting dashboard metrics: tenant={}", tenantId);
        DashboardMetricsResponse response = dashboardService.getMetrics(tenantId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/group-timeline")
    public ResponseEntity<GroupTimelineResponse> getGroupTimeline(
            @RequestParam("group_type") String groupType,
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(defaultValue = "Europe/Moscow") String timezone,
            @RequestParam(name = "tenant_id", required = false) UUID tenantIdParam,
            HttpServletRequest httpRequest) {

        DevicePrincipal principal = getPrincipalWithPermission(httpRequest, PERMISSION_CATALOGS_READ);
        UUID tenantId = resolveEffectiveTenantId(principal, tenantIdParam);
        GroupType type = parseGroupType(groupType);

        log.debug("Getting group timeline: tenant={} groupType={} from={} to={}", tenantId, type, from, to);
        GroupTimelineResponse response = dashboardService.getGroupTimeline(tenantId, type, from, to, timezone);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/group-summary")
    public ResponseEntity<GroupSummaryResponse> getGroupSummary(
            @RequestParam("group_type") String groupType,
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(name = "tenant_id", required = false) UUID tenantIdParam,
            HttpServletRequest httpRequest) {

        DevicePrincipal principal = getPrincipalWithPermission(httpRequest, PERMISSION_CATALOGS_READ);
        UUID tenantId = resolveEffectiveTenantId(principal, tenantIdParam);
        GroupType type = parseGroupType(groupType);

        log.debug("Getting group summary: tenant={} groupType={} from={} to={}", tenantId, type, from, to);
        GroupSummaryResponse response = dashboardService.getGroupSummary(tenantId, type, from, to);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/top-ungrouped")
    public ResponseEntity<TopUngroupedResponse> getTopUngrouped(
            @RequestParam("item_type") String itemType,
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(name = "tenant_id", required = false) UUID tenantIdParam,
            HttpServletRequest httpRequest) {

        DevicePrincipal principal = getPrincipalWithPermission(httpRequest, PERMISSION_CATALOGS_READ);
        UUID tenantId = resolveEffectiveTenantId(principal, tenantIdParam);
        ItemType type = parseItemType(itemType);

        log.debug("Getting top ungrouped: tenant={} itemType={} from={} to={}", tenantId, type, from, to);
        TopUngroupedResponse response = dashboardService.getTopUngrouped(tenantId, type, from, to, limit);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/top-users-ungrouped")
    public ResponseEntity<TopUsersUngroupedResponse> getTopUsersUngrouped(
            @RequestParam("item_type") String itemType,
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(name = "tenant_id", required = false) UUID tenantIdParam,
            HttpServletRequest httpRequest) {

        DevicePrincipal principal = getPrincipalWithPermission(httpRequest, PERMISSION_CATALOGS_READ);
        UUID tenantId = resolveEffectiveTenantId(principal, tenantIdParam);
        ItemType type = parseItemType(itemType);

        log.debug("Getting top users ungrouped: tenant={} itemType={} from={} to={}", tenantId, type, from, to);
        TopUsersUngroupedResponse response = dashboardService.getTopUsersUngrouped(tenantId, type, from, to, limit);
        return ResponseEntity.ok(response);
    }

    // ---- Security helpers ----

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

    private UUID resolveEffectiveTenantId(DevicePrincipal principal, UUID tenantIdParam) {
        if (principal.hasScope("global")) {
            return tenantIdParam;
        }
        return principal.getTenantId();
    }

    private GroupType parseGroupType(String groupType) {
        try {
            return GroupType.valueOf(groupType.toUpperCase());
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid group_type: " + groupType + ". Must be APP or SITE");
        }
    }

    private ItemType parseItemType(String itemType) {
        try {
            return ItemType.valueOf(itemType.toUpperCase());
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid item_type: " + itemType + ". Must be APP or SITE");
        }
    }
}
