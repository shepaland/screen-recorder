package com.prg.ingest.service.catalog;

import com.prg.ingest.dto.catalog.*;
import com.prg.ingest.entity.catalog.AppAlias;
import com.prg.ingest.entity.catalog.AppAlias.AliasType;
import com.prg.ingest.entity.catalog.AppGroup;
import com.prg.ingest.entity.catalog.AppGroup.GroupType;
import com.prg.ingest.entity.catalog.AppGroupItem;
import com.prg.ingest.entity.catalog.AppGroupItem.ItemType;
import com.prg.ingest.repository.catalog.AppAliasRepository;
import com.prg.ingest.repository.catalog.AppGroupItemRepository;
import com.prg.ingest.repository.catalog.AppGroupRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class DashboardService {

    private final EntityManager em;
    private final AppGroupRepository groupRepo;
    private final AppGroupItemRepository itemRepo;
    private final AppAliasRepository aliasRepo;
    private final CatalogSeedService seedService;

    /**
     * Dashboard metrics: connected devices, total devices, active users, tokens, video size.
     */
    @Transactional(readOnly = true)
    public DashboardMetricsResponse getMetrics(UUID tenantId) {
        // Total devices
        String totalDevicesSql = """
                SELECT COUNT(*)
                FROM devices
                WHERE (CAST(:tenantId AS uuid) IS NULL OR tenant_id = :tenantId)
                  AND is_deleted = false
                """;
        long totalDevices = queryLong(totalDevicesSql, tenantId);

        // Connected devices (last heartbeat within 5 minutes)
        String connectedDevicesSql = """
                SELECT COUNT(*)
                FROM devices
                WHERE (CAST(:tenantId AS uuid) IS NULL OR tenant_id = :tenantId)
                  AND is_deleted = false
                  AND last_heartbeat_ts > NOW() - INTERVAL '5 minutes'
                """;
        long connectedDevices = queryLong(connectedDevicesSql, tenantId);

        // Active users (users with activity in last 24 hours)
        String activeUsersSql = """
                SELECT COUNT(DISTINCT username)
                FROM app_focus_intervals
                WHERE (CAST(:tenantId AS uuid) IS NULL OR tenant_id = :tenantId)
                  AND started_at > NOW() - INTERVAL '24 hours'
                """;
        long activeUsers = queryLong(activeUsersSql, tenantId);

        // Tokens used / total
        String tokensSql = """
                SELECT COALESCE(SUM(current_uses), 0),
                       COUNT(*),
                       COALESCE(SUM(COALESCE(max_uses, 0)), 0)
                FROM device_registration_tokens
                WHERE (CAST(:tenantId AS uuid) IS NULL OR tenant_id = :tenantId)
                  AND is_active = true
                """;
        var tokensQuery = em.createNativeQuery(tokensSql)
                .setParameter("tenantId", tenantId);
        Object[] tokensRow = (Object[]) tokensQuery.getSingleResult();
        long tokensUsed = ((Number) tokensRow[0]).longValue();
        long tokensCount = ((Number) tokensRow[1]).longValue();
        long tokensMaxSum = ((Number) tokensRow[2]).longValue();
        long tokensTotal = tokensMaxSum > 0 ? tokensMaxSum : tokensCount;

        // Video size (sum of segment sizes)
        String videoSizeSql = """
                SELECT COALESCE(SUM(size_bytes), 0)
                FROM segments
                WHERE (CAST(:tenantId AS uuid) IS NULL OR tenant_id = :tenantId)
                """;
        long videoSizeBytes = queryLong(videoSizeSql, tenantId);

        return DashboardMetricsResponse.builder()
                .connectedDevices(connectedDevices)
                .totalDevices(totalDevices)
                .activeUsers(activeUsers)
                .tokensUsed(tokensUsed)
                .tokensTotal(tokensTotal)
                .videoSizeBytes(videoSizeBytes)
                .build();
    }

    /**
     * Group timeline: aggregated focus intervals by group per day.
     */
    @Transactional
    public GroupTimelineResponse getGroupTimeline(UUID tenantId, GroupType groupType,
                                                    String from, String to, String timezone) {
        return getGroupTimeline(tenantId, groupType, from, to, timezone, null);
    }

    /**
     * Group timeline: aggregated focus intervals by group per day.
     * Optionally filtered by employee group (includes group + children).
     */
    @Transactional
    public GroupTimelineResponse getGroupTimeline(UUID tenantId, GroupType groupType,
                                                    String from, String to, String timezone,
                                                    UUID employeeGroupId) {
        ensureGroupsExist(tenantId, groupType);

        List<AppGroup> groups = groupRepo.findByTenantIdAndGroupTypeOrderBySortOrder(tenantId, groupType);
        ItemType itemType = groupType == GroupType.APP ? ItemType.APP : ItemType.SITE;
        List<AppGroupItem> allItems = itemRepo.findByTenantIdAndItemType(tenantId, itemType);

        // Build pattern -> groupId map
        Map<String, UUID> patternToGroupId = new HashMap<>();
        UUID defaultGroupId = null;
        for (AppGroupItem item : allItems) {
            patternToGroupId.put(item.getPattern().toLowerCase(), item.getGroup().getId());
        }
        for (AppGroup g : groups) {
            if (g.isDefault()) {
                defaultGroupId = g.getId();
                break;
            }
        }

        // Query focus intervals per day
        boolean isApp = groupType == GroupType.APP;
        String valueColumn = isApp ? "process_name" : "domain";
        String extraFilter = isApp ? "" : " AND is_browser = true AND domain IS NOT NULL";

        // Employee group filter
        String employeeGroupFilter = "";
        if (employeeGroupId != null) {
            employeeGroupFilter = """
                     AND LOWER(username) IN (
                       SELECT LOWER(egm.username) FROM employee_group_members egm
                       WHERE egm.tenant_id = :tenantId
                         AND egm.group_id IN (SELECT id FROM employee_groups WHERE id = :employeeGroupId OR parent_id = :employeeGroupId)
                     )
                    """;
        }

        String sql = String.format("""
                SELECT DATE(started_at AT TIME ZONE :tz) AS d,
                       LOWER(%s) AS val,
                       SUM(duration_ms) AS dur
                FROM app_focus_intervals
                WHERE (CAST(:tenantId AS uuid) IS NULL OR tenant_id = :tenantId)
                  AND started_at >= CAST(:from AS timestamp) AT TIME ZONE :tz
                  AND started_at < (CAST(:to AS timestamp) + INTERVAL '1 day') AT TIME ZONE :tz
                  %s
                  %s
                GROUP BY d, val
                ORDER BY d
                """, valueColumn, extraFilter, employeeGroupFilter);

        var query = em.createNativeQuery(sql)
                .setParameter("tenantId", tenantId)
                .setParameter("from", from)
                .setParameter("to", to)
                .setParameter("tz", timezone);
        if (employeeGroupId != null) {
            query.setParameter("employeeGroupId", employeeGroupId);
        }

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();

        // Aggregate by day and group
        Map<String, Map<UUID, Long>> dayGroupDuration = new LinkedHashMap<>();
        Map<String, Long> dayTotals = new LinkedHashMap<>();

        UUID finalDefaultGroupId = defaultGroupId;
        for (Object[] row : rows) {
            String date = row[0].toString();
            String val = (String) row[1];
            long dur = ((Number) row[2]).longValue();

            UUID gid = patternToGroupId.getOrDefault(val, finalDefaultGroupId);
            dayGroupDuration.computeIfAbsent(date, k -> new HashMap<>())
                    .merge(gid, dur, Long::sum);
            dayTotals.merge(date, dur, Long::sum);
        }

        // Build response — exclude default group from groups (it becomes "ungrouped" on the chart)
        List<GroupTimelineResponse.GroupInfo> groupInfos = groups.stream()
                .filter(g -> !g.isDefault())
                .map(g -> GroupTimelineResponse.GroupInfo.builder()
                        .groupId(g.getId())
                        .groupName(g.getName())
                        .color(g.getColor())
                        .build())
                .toList();

        Map<UUID, String> groupNames = groups.stream()
                .collect(Collectors.toMap(AppGroup::getId, AppGroup::getName));

        List<GroupTimelineResponse.DayBreakdown> days = new ArrayList<>();
        for (var entry : dayGroupDuration.entrySet()) {
            String date = entry.getKey();
            Map<UUID, Long> groupDurations = entry.getValue();
            long dayTotal = dayTotals.getOrDefault(date, 0L);

            // Separate default group (ungrouped) from named groups
            long ungroupedDurationMs = 0;
            List<GroupTimelineResponse.GroupDuration> breakdown = new ArrayList<>();
            for (var e : groupDurations.entrySet()) {
                UUID gid = e.getKey();
                long dur = e.getValue();
                if (gid != null && gid.equals(finalDefaultGroupId)) {
                    ungroupedDurationMs = dur;
                } else {
                    breakdown.add(GroupTimelineResponse.GroupDuration.builder()
                            .groupId(gid)
                            .groupName(groupNames.getOrDefault(gid, "Прочее"))
                            .durationMs(dur)
                            .percentage(dayTotal > 0 ? (dur * 100.0 / dayTotal) : 0)
                            .build());
                }
            }
            breakdown.sort((a, b) -> Long.compare(b.getDurationMs(), a.getDurationMs()));

            days.add(GroupTimelineResponse.DayBreakdown.builder()
                    .date(date)
                    .totalDurationMs(dayTotal)
                    .breakdown(breakdown)
                    .ungroupedDurationMs(ungroupedDurationMs)
                    .ungroupedPercentage(dayTotal > 0 ? (ungroupedDurationMs * 100.0 / dayTotal) : 0)
                    .build());
        }

        return GroupTimelineResponse.builder()
                .groups(groupInfos)
                .days(days)
                .build();
    }

    /**
     * Group summary: total duration per group over a period.
     */
    @Transactional
    public GroupSummaryResponse getGroupSummary(UUID tenantId, GroupType groupType,
                                                 String from, String to) {
        ensureGroupsExist(tenantId, groupType);

        List<AppGroup> groups = groupRepo.findByTenantIdAndGroupTypeOrderBySortOrder(tenantId, groupType);
        ItemType itemType = groupType == GroupType.APP ? ItemType.APP : ItemType.SITE;
        List<AppGroupItem> allItems = itemRepo.findByTenantIdAndItemType(tenantId, itemType);

        Map<String, UUID> patternToGroupId = new HashMap<>();
        UUID defaultGroupId = null;
        for (AppGroupItem item : allItems) {
            patternToGroupId.put(item.getPattern().toLowerCase(), item.getGroup().getId());
        }
        for (AppGroup g : groups) {
            if (g.isDefault()) {
                defaultGroupId = g.getId();
                break;
            }
        }

        boolean isApp = groupType == GroupType.APP;
        String valueColumn = isApp ? "process_name" : "domain";
        String extraFilter = isApp ? "" : " AND is_browser = true AND domain IS NOT NULL";

        String sql = String.format("""
                SELECT LOWER(%s) AS val,
                       SUM(duration_ms) AS dur,
                       COUNT(DISTINCT username) AS ucnt
                FROM app_focus_intervals
                WHERE (CAST(:tenantId AS uuid) IS NULL OR tenant_id = :tenantId)
                  AND started_at >= CAST(:from AS timestamptz)
                  AND started_at < CAST(:to AS timestamptz) + INTERVAL '1 day'
                  %s
                GROUP BY val
                """, valueColumn, extraFilter);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(sql)
                .setParameter("tenantId", tenantId)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();

        Map<UUID, Long> groupDurations = new HashMap<>();
        Map<UUID, Set<String>> groupUsers = new HashMap<>();
        long totalDuration = 0;

        UUID finalDefaultGroupId = defaultGroupId;
        for (Object[] row : rows) {
            String val = (String) row[0];
            long dur = ((Number) row[1]).longValue();

            UUID gid = patternToGroupId.getOrDefault(val, finalDefaultGroupId);
            groupDurations.merge(gid, dur, Long::sum);
            totalDuration += dur;
        }

        // Separate query for user counts per group
        for (var group : groups) {
            groupUsers.put(group.getId(), new HashSet<>());
        }

        Map<UUID, String> groupNames = groups.stream()
                .collect(Collectors.toMap(AppGroup::getId, AppGroup::getName));
        Map<UUID, String> groupColors = groups.stream()
                .collect(Collectors.toMap(AppGroup::getId, g -> g.getColor() != null ? g.getColor() : "#9E9E9E"));

        long finalTotalDuration = totalDuration;
        List<GroupSummaryResponse.GroupTotal> groupTotals = groups.stream()
                .map(g -> {
                    long dur = groupDurations.getOrDefault(g.getId(), 0L);
                    return GroupSummaryResponse.GroupTotal.builder()
                            .groupId(g.getId())
                            .groupName(g.getName())
                            .color(g.getColor())
                            .durationMs(dur)
                            .percentage(finalTotalDuration > 0 ? (dur * 100.0 / finalTotalDuration) : 0)
                            .userCount(0) // simplified; full user count requires more complex query
                            .build();
                })
                .sorted((a, b) -> Long.compare(b.getDurationMs(), a.getDurationMs()))
                .toList();

        return GroupSummaryResponse.builder()
                .totalDurationMs(totalDuration)
                .groups(groupTotals)
                .build();
    }

    /**
     * Top ungrouped items by duration.
     */
    @Transactional
    public TopUngroupedResponse getTopUngrouped(UUID tenantId, ItemType itemType,
                                                  String from, String to, int limit) {
        if (limit < 1) limit = 10;
        if (limit > 100) limit = 100;

        GroupType groupType = itemType == ItemType.APP ? GroupType.APP : GroupType.SITE;
        ensureGroupsExist(tenantId, groupType);

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
                       COUNT(DISTINCT username) AS ucnt
                FROM app_focus_intervals
                WHERE (CAST(:tenantId AS uuid) IS NULL OR tenant_id = :tenantId)
                  AND started_at >= CAST(:from AS timestamptz)
                  AND started_at < CAST(:to AS timestamptz) + INTERVAL '1 day'
                  %s
                GROUP BY val
                ORDER BY dur DESC
                """, valueColumn, extraFilter);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(sql)
                .setParameter("tenantId", tenantId)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();

        // Filter out grouped items
        long totalUngroupedDuration = 0;
        List<Object[]> ungroupedRows = new ArrayList<>();
        for (Object[] row : rows) {
            String val = (String) row[0];
            if (!groupedPatterns.contains(val)) {
                ungroupedRows.add(row);
                totalUngroupedDuration += ((Number) row[1]).longValue();
            }
        }

        // Build alias map for display names
        AliasType aliasType = isApp ? AliasType.APP : AliasType.SITE;
        Map<String, String> aliasMap = buildAliasMap(tenantId, aliasType);

        long finalTotal = totalUngroupedDuration;
        List<TopUngroupedResponse.UngroupedItem> items = ungroupedRows.stream()
                .limit(limit)
                .map(row -> {
                    String val = (String) row[0];
                    long dur = ((Number) row[1]).longValue();
                    int ucnt = ((Number) row[2]).intValue();
                    return TopUngroupedResponse.UngroupedItem.builder()
                            .name(val)
                            .displayName(aliasMap.getOrDefault(val, val))
                            .totalDurationMs(dur)
                            .percentage(finalTotal > 0 ? (dur * 100.0 / finalTotal) : 0)
                            .userCount(ucnt)
                            .build();
                })
                .toList();

        return TopUngroupedResponse.builder().items(items).build();
    }

    /**
     * Top users by ungrouped duration.
     */
    @Transactional
    public TopUsersUngroupedResponse getTopUsersUngrouped(UUID tenantId, ItemType itemType,
                                                            String from, String to, int limit) {
        if (limit < 1) limit = 10;
        if (limit > 100) limit = 100;

        GroupType groupType = itemType == ItemType.APP ? GroupType.APP : GroupType.SITE;
        ensureGroupsExist(tenantId, groupType);

        List<AppGroupItem> allItems = itemRepo.findByTenantIdAndItemType(tenantId, itemType);
        Set<String> groupedPatterns = allItems.stream()
                .map(i -> i.getPattern().toLowerCase())
                .collect(Collectors.toSet());

        boolean isApp = itemType == ItemType.APP;
        String valueColumn = isApp ? "process_name" : "domain";
        String extraFilter = isApp ? "" : " AND is_browser = true AND domain IS NOT NULL";

        // Per user, per app/site
        String sql = String.format("""
                SELECT username,
                       LOWER(%s) AS val,
                       SUM(duration_ms) AS dur
                FROM app_focus_intervals
                WHERE (CAST(:tenantId AS uuid) IS NULL OR tenant_id = :tenantId)
                  AND started_at >= CAST(:from AS timestamptz)
                  AND started_at < CAST(:to AS timestamptz) + INTERVAL '1 day'
                  %s
                GROUP BY username, val
                """, valueColumn, extraFilter);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(sql)
                .setParameter("tenantId", tenantId)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();

        // Build user totals: grouped vs ungrouped
        Map<String, Long> userTotalDuration = new LinkedHashMap<>();
        Map<String, Long> userUngroupedDuration = new LinkedHashMap<>();
        Map<String, Map<String, Long>> userUngroupedItems = new LinkedHashMap<>();

        for (Object[] row : rows) {
            String username = (String) row[0];
            String val = (String) row[1];
            long dur = ((Number) row[2]).longValue();

            userTotalDuration.merge(username, dur, Long::sum);

            if (!groupedPatterns.contains(val)) {
                userUngroupedDuration.merge(username, dur, Long::sum);
                userUngroupedItems.computeIfAbsent(username, k -> new LinkedHashMap<>())
                        .merge(val, dur, Long::sum);
            }
        }

        AliasType aliasType = isApp ? AliasType.APP : AliasType.SITE;
        Map<String, String> aliasMap = buildAliasMap(tenantId, aliasType);

        // Get display names for users
        Map<String, String> userDisplayNames = getUserDisplayNames(tenantId);

        // Sort users by ungrouped duration desc and take top N
        List<TopUsersUngroupedResponse.UserUngrouped> users = userUngroupedDuration.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(limit)
                .map(entry -> {
                    String username = entry.getKey();
                    long ungroupedDur = entry.getValue();
                    long totalDur = userTotalDuration.getOrDefault(username, 0L);
                    double pct = totalDur > 0 ? (ungroupedDur * 100.0 / totalDur) : 0;

                    Map<String, Long> topItemsMap = userUngroupedItems.getOrDefault(username, Map.of());
                    List<TopUsersUngroupedResponse.UngroupedItem> topItems = topItemsMap.entrySet().stream()
                            .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                            .limit(5)
                            .map(e -> TopUsersUngroupedResponse.UngroupedItem.builder()
                                    .name(e.getKey())
                                    .displayName(aliasMap.getOrDefault(e.getKey(), e.getKey()))
                                    .durationMs(e.getValue())
                                    .build())
                            .toList();

                    return TopUsersUngroupedResponse.UserUngrouped.builder()
                            .username(username)
                            .displayName(userDisplayNames.getOrDefault(username, username))
                            .totalUngroupedDurationMs(ungroupedDur)
                            .ungroupedPercentage(Math.round(pct * 100.0) / 100.0)
                            .topUngroupedItems(topItems)
                            .build();
                })
                .toList();

        return TopUsersUngroupedResponse.builder().users(users).build();
    }

    // ---- Helpers ----

    private void ensureGroupsExist(UUID tenantId, GroupType groupType) {
        long count = groupRepo.countByTenantIdAndGroupType(tenantId, groupType);
        if (count == 0) {
            seedService.seed(tenantId, groupType, false);
        }
    }

    private long queryLong(String sql, UUID tenantId) {
        var query = em.createNativeQuery(sql).setParameter("tenantId", tenantId);
        return ((Number) query.getSingleResult()).longValue();
    }

    private Map<String, String> buildAliasMap(UUID tenantId, AliasType aliasType) {
        // Load all aliases for this tenant and type
        var page = aliasRepo.findByTenantIdAndAliasType(tenantId, aliasType,
                org.springframework.data.domain.PageRequest.of(0, 1000));
        return page.getContent().stream()
                .collect(Collectors.toMap(
                        a -> a.getOriginal().toLowerCase(),
                        AppAlias::getDisplayName,
                        (a, b) -> a));
    }

    private Map<String, String> getUserDisplayNames(UUID tenantId) {
        String sql = """
                SELECT username, display_name
                FROM device_user_sessions
                WHERE (CAST(:tenantId AS uuid) IS NULL OR tenant_id = :tenantId)
                  AND display_name IS NOT NULL
                """;
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(sql)
                .setParameter("tenantId", tenantId)
                .getResultList();

        return rows.stream()
                .collect(Collectors.toMap(
                        r -> (String) r[0],
                        r -> (String) r[1],
                        (a, b) -> a));
    }
}
