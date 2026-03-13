package com.prg.ingest.service.catalog;

import com.prg.ingest.dto.catalog.*;
import com.prg.ingest.dto.response.PageResponse;
import com.prg.ingest.entity.catalog.AppAlias;
import com.prg.ingest.entity.catalog.AppAlias.AliasType;
import com.prg.ingest.entity.catalog.AppGroup;
import com.prg.ingest.entity.catalog.AppGroup.GroupType;
import com.prg.ingest.entity.catalog.AppGroupItem;
import com.prg.ingest.entity.catalog.AppGroupItem.ItemType;
import com.prg.ingest.entity.catalog.AppGroupItem.MatchType;
import com.prg.ingest.exception.ResourceNotFoundException;
import com.prg.ingest.repository.catalog.AppAliasRepository;
import com.prg.ingest.repository.catalog.AppGroupItemRepository;
import com.prg.ingest.repository.catalog.AppGroupRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class CatalogService {

    private final AppGroupRepository groupRepo;
    private final AppGroupItemRepository itemRepo;
    private final AppAliasRepository aliasRepo;
    private final CatalogSeedService seedService;
    private final EntityManager em;

    // ---- Groups ----

    @Transactional
    public List<GroupResponse> getGroups(UUID tenantId, GroupType groupType) {
        // Lazy seed: auto-seed if no groups exist
        long count = groupRepo.countByTenantIdAndGroupType(tenantId, groupType);
        if (count == 0) {
            seedService.seed(tenantId, groupType, false);
        }

        List<AppGroup> groups = groupRepo.findByTenantIdAndGroupTypeOrderBySortOrder(tenantId, groupType);
        return groups.stream().map(this::toGroupResponse).toList();
    }

    @Transactional
    public GroupResponse createGroup(UUID tenantId, UUID userId, GroupCreateRequest request) {
        GroupType groupType = parseGroupType(request.getGroupType());

        if (groupRepo.existsByTenantIdAndGroupTypeAndNameIgnoreCase(tenantId, groupType, request.getName())) {
            throw new IllegalArgumentException("Group with name '" + request.getName() + "' already exists");
        }

        int sortOrder = request.getSortOrder() != null ? request.getSortOrder()
                : (int) groupRepo.countByTenantIdAndGroupType(tenantId, groupType) + 1;

        // is_browser_group only valid for APP groups
        boolean browserGroup = request.getIsBrowserGroup() != null && request.getIsBrowserGroup();
        if (browserGroup && groupType != GroupType.APP) {
            throw new IllegalArgumentException("is_browser_group can only be set for APP groups");
        }

        AppGroup group = AppGroup.builder()
                .tenantId(tenantId)
                .groupType(groupType)
                .name(request.getName())
                .description(request.getDescription())
                .color(request.getColor())
                .sortOrder(sortOrder)
                .isBrowserGroup(browserGroup)
                .createdBy(userId)
                .build();
        groupRepo.save(group);
        log.info("Created group: id={} name={} tenant={}", group.getId(), group.getName(), tenantId);

        return toGroupResponse(group);
    }

    @Transactional
    public GroupResponse updateGroup(UUID tenantId, UUID groupId, GroupUpdateRequest request) {
        AppGroup group = groupRepo.findByIdAndTenantId(groupId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Group not found: " + groupId));

        if (request.getName() != null && !request.getName().isBlank()) {
            // Check uniqueness if name is being changed
            if (!group.getName().equalsIgnoreCase(request.getName()) &&
                    groupRepo.existsByTenantIdAndGroupTypeAndNameIgnoreCase(tenantId, group.getGroupType(), request.getName())) {
                throw new IllegalArgumentException("Group with name '" + request.getName() + "' already exists");
            }
            group.setName(request.getName());
        }
        if (request.getDescription() != null) group.setDescription(request.getDescription());
        if (request.getColor() != null) group.setColor(request.getColor());
        if (request.getSortOrder() != null) group.setSortOrder(request.getSortOrder());
        if (request.getIsBrowserGroup() != null) {
            if (request.getIsBrowserGroup() && group.getGroupType() != GroupType.APP) {
                throw new IllegalArgumentException("is_browser_group can only be set for APP groups");
            }
            group.setBrowserGroup(request.getIsBrowserGroup());
        }

        groupRepo.save(group);
        log.info("Updated group: id={} tenant={}", groupId, tenantId);

        return toGroupResponse(group);
    }

    @Transactional
    public void deleteGroup(UUID tenantId, UUID groupId) {
        AppGroup group = groupRepo.findByIdAndTenantId(groupId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Group not found: " + groupId));

        if (group.isDefault()) {
            throw new IllegalArgumentException("Cannot delete the default group");
        }

        groupRepo.delete(group);
        log.info("Deleted group: id={} name={} tenant={}", groupId, group.getName(), tenantId);
    }

    // ---- Items ----

    @Transactional(readOnly = true)
    public List<GroupItemResponse> getGroupItems(UUID tenantId, UUID groupId) {
        AppGroup group = groupRepo.findByIdAndTenantId(groupId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Group not found: " + groupId));

        List<AppGroupItem> items = itemRepo.findByGroupId(groupId);
        return items.stream().map(this::toGroupItemResponse).toList();
    }

    @Transactional
    public GroupItemResponse createItem(UUID tenantId, UUID groupId, GroupItemCreateRequest request) {
        AppGroup group = groupRepo.findByIdAndTenantId(groupId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Group not found: " + groupId));

        ItemType itemType = group.getGroupType() == GroupType.APP ? ItemType.APP : ItemType.SITE;

        if (itemRepo.existsByTenantIdAndItemTypeAndPatternIgnoreCase(tenantId, itemType, request.getPattern())) {
            throw new IllegalArgumentException("Pattern '" + request.getPattern() + "' already exists in another group");
        }

        MatchType matchType = request.getMatchType() != null ?
                MatchType.valueOf(request.getMatchType().toUpperCase()) : MatchType.EXACT;

        AppGroupItem item = AppGroupItem.builder()
                .tenantId(tenantId)
                .group(group)
                .itemType(itemType)
                .pattern(request.getPattern())
                .matchType(matchType)
                .build();
        itemRepo.save(item);
        log.info("Created item: id={} pattern={} group={} tenant={}", item.getId(), item.getPattern(), groupId, tenantId);

        return toGroupItemResponse(item);
    }

    @Transactional
    public List<GroupItemResponse> batchCreateItems(UUID tenantId, UUID groupId, GroupItemBatchRequest request) {
        AppGroup group = groupRepo.findByIdAndTenantId(groupId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Group not found: " + groupId));

        ItemType itemType = group.getGroupType() == GroupType.APP ? ItemType.APP : ItemType.SITE;

        List<GroupItemResponse> results = new ArrayList<>();
        for (GroupItemCreateRequest itemReq : request.getItems()) {
            if (itemRepo.existsByTenantIdAndItemTypeAndPatternIgnoreCase(tenantId, itemType, itemReq.getPattern())) {
                log.warn("Skipping duplicate pattern: {}", itemReq.getPattern());
                continue;
            }

            MatchType matchType = itemReq.getMatchType() != null ?
                    MatchType.valueOf(itemReq.getMatchType().toUpperCase()) : MatchType.EXACT;

            AppGroupItem item = AppGroupItem.builder()
                    .tenantId(tenantId)
                    .group(group)
                    .itemType(itemType)
                    .pattern(itemReq.getPattern())
                    .matchType(matchType)
                    .build();
            itemRepo.save(item);
            results.add(toGroupItemResponse(item));
        }

        log.info("Batch created {} items for group={} tenant={}", results.size(), groupId, tenantId);
        return results;
    }

    @Transactional
    public void deleteItem(UUID tenantId, UUID itemId) {
        AppGroupItem item = itemRepo.findByIdAndTenantId(itemId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Item not found: " + itemId));
        itemRepo.delete(item);
        log.info("Deleted item: id={} pattern={} tenant={}", itemId, item.getPattern(), tenantId);
    }

    @Transactional
    public GroupItemResponse moveItem(UUID tenantId, UUID itemId, UUID targetGroupId) {
        AppGroupItem item = itemRepo.findByIdAndTenantId(itemId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Item not found: " + itemId));

        AppGroup targetGroup = groupRepo.findByIdAndTenantId(targetGroupId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Target group not found: " + targetGroupId));

        // Validate group_type matches
        ItemType expectedItemType = targetGroup.getGroupType() == GroupType.APP ? ItemType.APP : ItemType.SITE;
        if (item.getItemType() != expectedItemType) {
            throw new IllegalArgumentException("Cannot move item of type " + item.getItemType()
                    + " to group of type " + targetGroup.getGroupType());
        }

        item.setGroup(targetGroup);
        itemRepo.save(item);
        log.info("Moved item: id={} to group={} tenant={}", itemId, targetGroupId, tenantId);

        return toGroupItemResponse(item);
    }

    // ---- Ungrouped ----

    @Transactional
    public UngroupedPageResponse getUngrouped(UUID tenantId, ItemType itemType,
                                               int page, int size, String search) {
        if (page < 0) page = 0;
        if (size < 1) size = 1;
        if (size > 100) size = 100;

        GroupType groupType = itemType == ItemType.APP ? GroupType.APP : GroupType.SITE;

        // Ensure groups exist (lazy seed)
        long groupCount = groupRepo.countByTenantIdAndGroupType(tenantId, groupType);
        if (groupCount == 0) {
            seedService.seed(tenantId, groupType, false);
        }

        // Get all grouped patterns
        List<AppGroupItem> allItems = itemRepo.findByTenantIdAndItemType(tenantId, itemType);
        Set<String> groupedPatterns = allItems.stream()
                .map(i -> i.getPattern().toLowerCase())
                .collect(Collectors.toSet());

        boolean isApp = itemType == ItemType.APP;
        String valueColumn = isApp ? "process_name" : "domain";
        String extraFilter = isApp ? "" : " AND is_browser = true AND domain IS NOT NULL";

        String sql = String.format("""
                SELECT LOWER(%s) AS val,
                       SUM(duration_ms) AS dur,
                       COUNT(*) AS icnt,
                       COUNT(DISTINCT username) AS ucnt,
                       MAX(started_at) AS last_seen
                FROM app_focus_intervals
                WHERE tenant_id = :tenantId
                  %s
                GROUP BY val
                ORDER BY dur DESC
                """, valueColumn, extraFilter);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(sql)
                .setParameter("tenantId", tenantId)
                .getResultList();

        // Filter out grouped items and apply search
        String searchLower = search != null && !search.isBlank() ? search.toLowerCase() : null;
        List<Object[]> ungroupedRows = new ArrayList<>();
        long totalUngroupedDuration = 0;
        for (Object[] row : rows) {
            String val = (String) row[0];
            if (groupedPatterns.contains(val)) continue;
            totalUngroupedDuration += ((Number) row[1]).longValue();
            if (searchLower != null && !val.contains(searchLower)) continue;
            ungroupedRows.add(row);
        }

        // Build alias map for display names
        AppAlias.AliasType aliasType = isApp ? AppAlias.AliasType.APP : AppAlias.AliasType.SITE;
        Map<String, String> aliasMap = aliasRepo.findByTenantIdAndAliasType(tenantId, aliasType,
                        PageRequest.of(0, 10000)).getContent().stream()
                .collect(Collectors.toMap(
                        a -> a.getOriginal().toLowerCase(),
                        AppAlias::getDisplayName,
                        (a, b) -> a));

        // Paginate
        int totalElements = ungroupedRows.size();
        int totalPages = (int) Math.ceil((double) totalElements / size);
        int fromIdx = Math.min(page * size, totalElements);
        int toIdx = Math.min(fromIdx + size, totalElements);
        List<Object[]> pageRows = ungroupedRows.subList(fromIdx, toIdx);

        List<UngroupedPageResponse.UngroupedItem> content = pageRows.stream()
                .map(row -> {
                    String val = (String) row[0];
                    long dur = ((Number) row[1]).longValue();
                    int icnt = ((Number) row[2]).intValue();
                    int ucnt = ((Number) row[3]).intValue();
                    Object lastSeenObj = row[4];
                    Instant lastSeen = null;
                    if (lastSeenObj instanceof Instant inst) {
                        lastSeen = inst;
                    } else if (lastSeenObj instanceof java.sql.Timestamp ts) {
                        lastSeen = ts.toInstant();
                    } else if (lastSeenObj instanceof java.time.OffsetDateTime odt) {
                        lastSeen = odt.toInstant();
                    }
                    return UngroupedPageResponse.UngroupedItem.builder()
                            .name(val)
                            .displayName(aliasMap.get(val))
                            .totalDurationMs(dur)
                            .intervalCount(icnt)
                            .userCount(ucnt)
                            .lastSeenAt(lastSeen)
                            .build();
                })
                .toList();

        return UngroupedPageResponse.builder()
                .content(content)
                .page(page)
                .size(size)
                .totalElements(totalElements)
                .totalPages(totalPages)
                .totalUngroupedDurationMs(totalUngroupedDuration)
                .build();
    }

    // ---- Aliases ----

    @Transactional(readOnly = true)
    public PageResponse<AliasResponse> getAliases(UUID tenantId, AliasType aliasType,
                                                    int page, int size, String search) {
        if (page < 0) page = 0;
        if (size < 1) size = 1;
        if (size > 100) size = 100;

        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "original"));

        Page<AppAlias> aliasPage;
        if (search != null && !search.isBlank()) {
            aliasPage = aliasRepo.findByTenantIdAndAliasTypeAndSearch(tenantId, aliasType, search, pageRequest);
        } else {
            aliasPage = aliasRepo.findByTenantIdAndAliasType(tenantId, aliasType, pageRequest);
        }

        List<AliasResponse> content = aliasPage.getContent().stream()
                .map(this::toAliasResponse)
                .toList();

        return PageResponse.<AliasResponse>builder()
                .content(content)
                .page(page)
                .size(size)
                .totalElements(aliasPage.getTotalElements())
                .totalPages(aliasPage.getTotalPages())
                .build();
    }

    @Transactional
    public AliasResponse createAlias(UUID tenantId, AliasCreateRequest request) {
        AliasType aliasType = AliasType.valueOf(request.getAliasType().toUpperCase());

        if (aliasRepo.existsByTenantIdAndAliasTypeAndOriginalIgnoreCase(tenantId, aliasType, request.getOriginal())) {
            throw new IllegalArgumentException("Alias for '" + request.getOriginal() + "' already exists");
        }

        AppAlias alias = AppAlias.builder()
                .tenantId(tenantId)
                .aliasType(aliasType)
                .original(request.getOriginal())
                .displayName(request.getDisplayName())
                .iconUrl(request.getIconUrl())
                .build();
        aliasRepo.save(alias);
        log.info("Created alias: id={} original={} tenant={}", alias.getId(), alias.getOriginal(), tenantId);

        return toAliasResponse(alias);
    }

    @Transactional
    public AliasResponse updateAlias(UUID tenantId, UUID aliasId, AliasUpdateRequest request) {
        AppAlias alias = aliasRepo.findByIdAndTenantId(aliasId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Alias not found: " + aliasId));

        if (request.getDisplayName() != null && !request.getDisplayName().isBlank()) {
            alias.setDisplayName(request.getDisplayName());
        }
        if (request.getIconUrl() != null) {
            alias.setIconUrl(request.getIconUrl());
        }

        aliasRepo.save(alias);
        log.info("Updated alias: id={} tenant={}", aliasId, tenantId);

        return toAliasResponse(alias);
    }

    @Transactional
    public void deleteAlias(UUID tenantId, UUID aliasId) {
        AppAlias alias = aliasRepo.findByIdAndTenantId(aliasId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Alias not found: " + aliasId));
        aliasRepo.delete(alias);
        log.info("Deleted alias: id={} original={} tenant={}", aliasId, alias.getOriginal(), tenantId);
    }

    // ---- Mapping helpers ----

    private GroupResponse toGroupResponse(AppGroup group) {
        int itemCount = group.getItems() != null ? group.getItems().size() : 0;
        List<GroupItemResponse> items = group.getItems() != null ?
                group.getItems().stream().map(this::toGroupItemResponse).toList() : List.of();

        return GroupResponse.builder()
                .id(group.getId())
                .groupType(group.getGroupType().name())
                .name(group.getName())
                .description(group.getDescription())
                .color(group.getColor())
                .sortOrder(group.getSortOrder())
                .isDefault(group.isDefault())
                .isBrowserGroup(group.isBrowserGroup())
                .itemCount(itemCount)
                .items(items)
                .createdAt(group.getCreatedAt())
                .updatedAt(group.getUpdatedAt())
                .build();
    }

    private GroupItemResponse toGroupItemResponse(AppGroupItem item) {
        return GroupItemResponse.builder()
                .id(item.getId())
                .groupId(item.getGroup().getId())
                .itemType(item.getItemType().name())
                .pattern(item.getPattern())
                .matchType(item.getMatchType().name())
                .createdAt(item.getCreatedAt())
                .build();
    }

    private AliasResponse toAliasResponse(AppAlias alias) {
        return AliasResponse.builder()
                .id(alias.getId())
                .aliasType(alias.getAliasType().name())
                .original(alias.getOriginal())
                .displayName(alias.getDisplayName())
                .iconUrl(alias.getIconUrl())
                .createdAt(alias.getCreatedAt())
                .updatedAt(alias.getUpdatedAt())
                .build();
    }

    private GroupType parseGroupType(String groupType) {
        try {
            return GroupType.valueOf(groupType.toUpperCase());
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid group_type: " + groupType + ". Must be APP or SITE");
        }
    }
}
