package com.prg.ingest.controller;

import com.prg.ingest.dto.catalog.*;
import com.prg.ingest.dto.response.PageResponse;
import com.prg.ingest.entity.catalog.AppAlias.AliasType;
import com.prg.ingest.entity.catalog.AppGroup.GroupType;
import com.prg.ingest.exception.AccessDeniedException;
import com.prg.ingest.filter.JwtValidationFilter;
import com.prg.ingest.security.DevicePrincipal;
import com.prg.ingest.service.catalog.CatalogSeedService;
import com.prg.ingest.service.catalog.CatalogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ingest/catalogs")
@RequiredArgsConstructor
@Slf4j
public class CatalogController {

    private static final String PERMISSION_CATALOGS_READ = "CATALOGS:READ";
    private static final String PERMISSION_CATALOGS_MANAGE = "CATALOGS:MANAGE";

    private final CatalogService catalogService;
    private final CatalogSeedService seedService;

    // ---- Groups ----

    @GetMapping("/groups")
    public ResponseEntity<List<GroupResponse>> getGroups(
            @RequestParam("group_type") String groupType,
            @RequestParam(name = "tenant_id", required = false) UUID tenantIdParam,
            HttpServletRequest httpRequest) {

        DevicePrincipal principal = getPrincipalWithPermission(httpRequest, PERMISSION_CATALOGS_READ);
        UUID tenantId = resolveEffectiveTenantId(principal, tenantIdParam);
        GroupType type = parseGroupType(groupType);

        List<GroupResponse> groups = catalogService.getGroups(tenantId, type);
        return ResponseEntity.ok(groups);
    }

    @PostMapping("/groups")
    public ResponseEntity<GroupResponse> createGroup(
            @Valid @RequestBody GroupCreateRequest request,
            @RequestParam(name = "tenant_id", required = false) UUID tenantIdParam,
            HttpServletRequest httpRequest) {

        DevicePrincipal principal = getPrincipalWithPermission(httpRequest, PERMISSION_CATALOGS_MANAGE);
        UUID tenantId = resolveEffectiveTenantId(principal, tenantIdParam);

        GroupResponse response = catalogService.createGroup(tenantId, principal.getUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/groups/{groupId}")
    public ResponseEntity<GroupResponse> updateGroup(
            @PathVariable UUID groupId,
            @Valid @RequestBody GroupUpdateRequest request,
            @RequestParam(name = "tenant_id", required = false) UUID tenantIdParam,
            HttpServletRequest httpRequest) {

        DevicePrincipal principal = getPrincipalWithPermission(httpRequest, PERMISSION_CATALOGS_MANAGE);
        UUID tenantId = resolveEffectiveTenantId(principal, tenantIdParam);

        GroupResponse response = catalogService.updateGroup(tenantId, groupId, request);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/groups/{groupId}")
    public ResponseEntity<Void> deleteGroup(
            @PathVariable UUID groupId,
            @RequestParam(name = "tenant_id", required = false) UUID tenantIdParam,
            HttpServletRequest httpRequest) {

        DevicePrincipal principal = getPrincipalWithPermission(httpRequest, PERMISSION_CATALOGS_MANAGE);
        UUID tenantId = resolveEffectiveTenantId(principal, tenantIdParam);

        catalogService.deleteGroup(tenantId, groupId);
        return ResponseEntity.noContent().build();
    }

    // ---- Items ----

    @GetMapping("/groups/{groupId}/items")
    public ResponseEntity<List<GroupItemResponse>> getGroupItems(
            @PathVariable UUID groupId,
            @RequestParam(name = "tenant_id", required = false) UUID tenantIdParam,
            HttpServletRequest httpRequest) {

        DevicePrincipal principal = getPrincipalWithPermission(httpRequest, PERMISSION_CATALOGS_READ);
        UUID tenantId = resolveEffectiveTenantId(principal, tenantIdParam);

        List<GroupItemResponse> items = catalogService.getGroupItems(tenantId, groupId);
        return ResponseEntity.ok(items);
    }

    @PostMapping("/groups/{groupId}/items")
    public ResponseEntity<GroupItemResponse> createItem(
            @PathVariable UUID groupId,
            @Valid @RequestBody GroupItemCreateRequest request,
            @RequestParam(name = "tenant_id", required = false) UUID tenantIdParam,
            HttpServletRequest httpRequest) {

        DevicePrincipal principal = getPrincipalWithPermission(httpRequest, PERMISSION_CATALOGS_MANAGE);
        UUID tenantId = resolveEffectiveTenantId(principal, tenantIdParam);

        GroupItemResponse response = catalogService.createItem(tenantId, groupId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/groups/{groupId}/items/batch")
    public ResponseEntity<List<GroupItemResponse>> batchCreateItems(
            @PathVariable UUID groupId,
            @Valid @RequestBody GroupItemBatchRequest request,
            @RequestParam(name = "tenant_id", required = false) UUID tenantIdParam,
            HttpServletRequest httpRequest) {

        DevicePrincipal principal = getPrincipalWithPermission(httpRequest, PERMISSION_CATALOGS_MANAGE);
        UUID tenantId = resolveEffectiveTenantId(principal, tenantIdParam);

        List<GroupItemResponse> items = catalogService.batchCreateItems(tenantId, groupId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(items);
    }

    @DeleteMapping("/items/{itemId}")
    public ResponseEntity<Void> deleteItem(
            @PathVariable UUID itemId,
            @RequestParam(name = "tenant_id", required = false) UUID tenantIdParam,
            HttpServletRequest httpRequest) {

        DevicePrincipal principal = getPrincipalWithPermission(httpRequest, PERMISSION_CATALOGS_MANAGE);
        UUID tenantId = resolveEffectiveTenantId(principal, tenantIdParam);

        catalogService.deleteItem(tenantId, itemId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/items/{itemId}/move")
    public ResponseEntity<GroupItemResponse> moveItem(
            @PathVariable UUID itemId,
            @Valid @RequestBody ItemMoveRequest request,
            @RequestParam(name = "tenant_id", required = false) UUID tenantIdParam,
            HttpServletRequest httpRequest) {

        DevicePrincipal principal = getPrincipalWithPermission(httpRequest, PERMISSION_CATALOGS_MANAGE);
        UUID tenantId = resolveEffectiveTenantId(principal, tenantIdParam);

        GroupItemResponse response = catalogService.moveItem(tenantId, itemId, request.getTargetGroupId());
        return ResponseEntity.ok(response);
    }

    // ---- Aliases ----

    @GetMapping("/aliases")
    public ResponseEntity<PageResponse<AliasResponse>> getAliases(
            @RequestParam("alias_type") String aliasType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String search,
            @RequestParam(name = "tenant_id", required = false) UUID tenantIdParam,
            HttpServletRequest httpRequest) {

        DevicePrincipal principal = getPrincipalWithPermission(httpRequest, PERMISSION_CATALOGS_READ);
        UUID tenantId = resolveEffectiveTenantId(principal, tenantIdParam);
        AliasType type = parseAliasType(aliasType);

        PageResponse<AliasResponse> response = catalogService.getAliases(tenantId, type, page, size, search);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/aliases")
    public ResponseEntity<AliasResponse> createAlias(
            @Valid @RequestBody AliasCreateRequest request,
            @RequestParam(name = "tenant_id", required = false) UUID tenantIdParam,
            HttpServletRequest httpRequest) {

        DevicePrincipal principal = getPrincipalWithPermission(httpRequest, PERMISSION_CATALOGS_MANAGE);
        UUID tenantId = resolveEffectiveTenantId(principal, tenantIdParam);

        AliasResponse response = catalogService.createAlias(tenantId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/aliases/{aliasId}")
    public ResponseEntity<AliasResponse> updateAlias(
            @PathVariable UUID aliasId,
            @Valid @RequestBody AliasUpdateRequest request,
            @RequestParam(name = "tenant_id", required = false) UUID tenantIdParam,
            HttpServletRequest httpRequest) {

        DevicePrincipal principal = getPrincipalWithPermission(httpRequest, PERMISSION_CATALOGS_MANAGE);
        UUID tenantId = resolveEffectiveTenantId(principal, tenantIdParam);

        AliasResponse response = catalogService.updateAlias(tenantId, aliasId, request);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/aliases/{aliasId}")
    public ResponseEntity<Void> deleteAlias(
            @PathVariable UUID aliasId,
            @RequestParam(name = "tenant_id", required = false) UUID tenantIdParam,
            HttpServletRequest httpRequest) {

        DevicePrincipal principal = getPrincipalWithPermission(httpRequest, PERMISSION_CATALOGS_MANAGE);
        UUID tenantId = resolveEffectiveTenantId(principal, tenantIdParam);

        catalogService.deleteAlias(tenantId, aliasId);
        return ResponseEntity.noContent().build();
    }

    // ---- Seed ----

    @PostMapping("/seed")
    public ResponseEntity<Void> seed(
            @RequestParam("group_type") String groupType,
            @RequestParam(defaultValue = "false") boolean force,
            @RequestParam(name = "tenant_id", required = false) UUID tenantIdParam,
            HttpServletRequest httpRequest) {

        DevicePrincipal principal = getPrincipalWithPermission(httpRequest, PERMISSION_CATALOGS_MANAGE);
        UUID tenantId = resolveEffectiveTenantId(principal, tenantIdParam);
        GroupType type = parseGroupType(groupType);

        seedService.seed(tenantId, type, force);
        return ResponseEntity.ok().build();
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
            if (tenantIdParam == null) {
                throw new IllegalArgumentException("tenant_id is required for global scope. Specify tenant_id query parameter.");
            }
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

    private AliasType parseAliasType(String aliasType) {
        try {
            return AliasType.valueOf(aliasType.toUpperCase());
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid alias_type: " + aliasType + ". Must be APP or SITE");
        }
    }
}
