package com.prg.ingest.service;

import com.prg.ingest.dto.response.*;
import com.prg.ingest.entity.catalog.AppGroup;
import com.prg.ingest.entity.catalog.AppGroupItem;
import com.prg.ingest.repository.catalog.AppGroupItemRepository;
import com.prg.ingest.repository.catalog.AppGroupRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class TimelineService {

    private final EntityManager em;
    private final AppGroupRepository appGroupRepository;
    private final AppGroupItemRepository appGroupItemRepository;

    // Simple in-memory cache for app groups: tenantId -> (groups, expiry)
    private final ConcurrentHashMap<UUID, CachedGroups> groupsCache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 5 * 60 * 1000L; // 5 minutes

    @Transactional(readOnly = true)
    public TimelineResponse getTimeline(UUID tenantId, String date, String timezone) {
        // Validate date format early (T-156: return 400 instead of 500)
        try {
            LocalDate.parse(date);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid date format: " + date + ". Expected: yyyy-MM-dd");
        }

        // Validate timezone early — only accept IANA zone IDs (e.g. Europe/Moscow), reject arbitrary offsets
        if (!ZoneId.getAvailableZoneIds().contains(timezone)) {
            throw new IllegalArgumentException("Invalid timezone: " + timezone + ". Use IANA timezone IDs (e.g. Europe/Moscow, UTC)");
        }

        log.debug("Building timeline: tenant={}, date={}, tz={}", tenantId, date, timezone);

        // 1. Focus intervals aggregated by username, hour, process_name, is_browser, domain
        List<FocusRow> focusRows = queryFocusIntervals(tenantId, date, timezone);

        // 2. Device-user mapping (display names)
        Map<String, UserInfo> userInfoMap = queryDeviceUserSessions(tenantId);

        // 3. Load app/site group rules (with cache)
        GroupRules groupRules = loadGroupRules(tenantId);

        // 4. Assemble response
        return assembleTimeline(date, timezone, focusRows, userInfoMap, groupRules);
    }

    // ============================ SQL Queries ============================

    private List<FocusRow> queryFocusIntervals(UUID tenantId, String date, String timezone) {
        String sql = """
                SELECT username,
                       EXTRACT(HOUR FROM started_at AT TIME ZONE :tz)::int AS hour,
                       process_name,
                       is_browser,
                       domain,
                       SUM(duration_ms) AS total_duration_ms,
                       ARRAY_AGG(DISTINCT device_id) AS device_ids,
                       ARRAY_AGG(DISTINCT session_id) FILTER (WHERE session_id IS NOT NULL) AS session_ids
                FROM app_focus_intervals
                WHERE (CAST(:tenantId AS uuid) IS NULL OR tenant_id = :tenantId)
                  AND started_at >= CAST(:date AS timestamp) AT TIME ZONE :tz
                  AND started_at < (CAST(:date AS timestamp) + INTERVAL '1 day') AT TIME ZONE :tz
                GROUP BY username, hour, process_name, is_browser, domain
                ORDER BY username, hour, total_duration_ms DESC
                LIMIT 50000
                """;

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(sql)
                .setParameter("tenantId", tenantId)
                .setParameter("date", date)
                .setParameter("tz", timezone)
                .getResultList();

        return rows.stream().map(r -> new FocusRow(
                (String) r[0],                           // username
                ((Number) r[1]).intValue(),               // hour
                (String) r[2],                            // processName
                r[3] != null && (Boolean) r[3],           // isBrowser
                (String) r[4],                            // domain
                ((Number) r[5]).longValue(),               // totalDurationMs
                parseUuidArray(r[6]),                      // deviceIds
                parseUuidArray(r[7])                       // sessionIds
        )).toList();
    }

    private Map<String, UserInfo> queryDeviceUserSessions(UUID tenantId) {
        String sql = """
                SELECT device_id, username, display_name
                FROM device_user_sessions
                WHERE (CAST(:tenantId AS uuid) IS NULL OR tenant_id = :tenantId)
                  AND is_active = true
                LIMIT 10000
                """;

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(sql)
                .setParameter("tenantId", tenantId)
                .getResultList();

        // username -> UserInfo (collecting deviceIds)
        Map<String, UserInfo> map = new LinkedHashMap<>();
        for (Object[] r : rows) {
            UUID deviceId = toUuid(r[0]);
            String username = (String) r[1];
            String displayName = (String) r[2];

            map.computeIfAbsent(username, k -> new UserInfo(k, displayName, new ArrayList<>()))
                    .deviceIds.add(deviceId);
        }
        return map;
    }

    // ============================ Group Rules ============================

    private GroupRules loadGroupRules(UUID tenantId) {
        if (tenantId == null) {
            // Global scope: no group rules
            return new GroupRules(List.of(), List.of());
        }

        CachedGroups cached = groupsCache.get(tenantId);
        if (cached != null && System.currentTimeMillis() < cached.expiresAt) {
            return cached.rules;
        }

        List<AppGroup> appGroups = appGroupRepository.findByTenantIdAndGroupTypeOrderBySortOrder(
                tenantId, AppGroup.GroupType.APP);
        List<AppGroup> siteGroups = appGroupRepository.findByTenantIdAndGroupTypeOrderBySortOrder(
                tenantId, AppGroup.GroupType.SITE);

        // Eagerly load items for each group
        List<AppGroupWithItems> appRules = appGroups.stream().map(g -> {
            List<AppGroupItem> items = appGroupItemRepository.findByGroupId(g.getId());
            return new AppGroupWithItems(g.getId(), g.getName(), g.getColor(), items);
        }).toList();

        List<AppGroupWithItems> siteRules = siteGroups.stream().map(g -> {
            List<AppGroupItem> items = appGroupItemRepository.findByGroupId(g.getId());
            return new AppGroupWithItems(g.getId(), g.getName(), g.getColor(), items);
        }).toList();

        GroupRules rules = new GroupRules(appRules, siteRules);
        groupsCache.put(tenantId, new CachedGroups(rules, System.currentTimeMillis() + CACHE_TTL_MS));
        return rules;
    }

    // ============================ Assembly ============================

    private TimelineResponse assembleTimeline(
            String date, String timezone,
            List<FocusRow> focusRows,
            Map<String, UserInfo> userInfoMap,
            GroupRules groupRules) {

        // Group focus rows by username
        Map<String, List<FocusRow>> byUser = focusRows.stream()
                .collect(Collectors.groupingBy(r -> r.username, LinkedHashMap::new, Collectors.toList()));

        // Also include users from userInfoMap that might not have focus data
        List<TimelineUser> timelineUsers = new ArrayList<>();

        for (Map.Entry<String, List<FocusRow>> entry : byUser.entrySet()) {
            String username = entry.getKey();
            List<FocusRow> userFocus = entry.getValue();

            // Resolve display name and device IDs
            UserInfo info = userInfoMap.get(username);
            String displayName = info != null ? info.displayName : null;
            Set<UUID> allDeviceIds = new LinkedHashSet<>();
            if (info != null) {
                allDeviceIds.addAll(info.deviceIds);
            }
            userFocus.forEach(f -> allDeviceIds.addAll(f.deviceIds));

            // Group by hour
            Map<Integer, List<FocusRow>> byHour = userFocus.stream()
                    .collect(Collectors.groupingBy(r -> r.hour, TreeMap::new, Collectors.toList()));

            List<TimelineHour> hours = new ArrayList<>();
            for (Map.Entry<Integer, List<FocusRow>> hourEntry : byHour.entrySet()) {
                int hour = hourEntry.getKey();
                List<FocusRow> hourRows = hourEntry.getValue();

                long totalDurationMs = hourRows.stream().mapToLong(r -> r.totalDurationMs).sum();

                // Collect all session IDs from this hour's focus rows
                Set<UUID> hourSessionIds = new LinkedHashSet<>();
                hourRows.forEach(r -> hourSessionIds.addAll(r.sessionIds));

                boolean hasRecording = !hourSessionIds.isEmpty();

                // Build app groups for this hour
                List<TimelineAppGroup> appGroups = buildAppGroups(hourRows, groupRules, hourSessionIds);

                hours.add(TimelineHour.builder()
                        .hour(hour)
                        .totalDurationMs(totalDurationMs)
                        .hasRecording(hasRecording)
                        .recordingSessionIds(new ArrayList<>(hourSessionIds))
                        .appGroups(appGroups)
                        .build());
            }

            timelineUsers.add(TimelineUser.builder()
                    .username(username)
                    .displayName(displayName)
                    .deviceIds(new ArrayList<>(allDeviceIds))
                    .hours(hours)
                    .build());
        }

        // T-160: Include active users from device_user_sessions that have no focus data for this day
        for (Map.Entry<String, UserInfo> entry : userInfoMap.entrySet()) {
            String username = entry.getKey();
            if (!byUser.containsKey(username)) {
                UserInfo info = entry.getValue();
                timelineUsers.add(TimelineUser.builder()
                        .username(username)
                        .displayName(info.displayName)
                        .deviceIds(new ArrayList<>(info.deviceIds))
                        .hours(List.of())
                        .build());
            }
        }

        return TimelineResponse.builder()
                .date(date)
                .timezone(timezone)
                .users(timelineUsers)
                .build();
    }

    private List<TimelineAppGroup> buildAppGroups(
            List<FocusRow> hourRows,
            GroupRules groupRules,
            Set<UUID> hourSessionIds) {

        // Separate browser vs non-browser rows
        List<FocusRow> nonBrowserRows = hourRows.stream().filter(r -> !r.isBrowser).toList();
        List<FocusRow> browserRows = hourRows.stream().filter(r -> r.isBrowser).toList();

        List<TimelineAppGroup> result = new ArrayList<>();

        // --- Non-browser apps: match to APP groups ---
        // Map<groupId (null=ungrouped), groupName, color, list of apps>
        Map<UUID, AppGroupAccumulator> appGroupMap = new LinkedHashMap<>();

        for (FocusRow row : nonBrowserRows) {
            AppGroupWithItems matched = matchAppGroup(row.processName, groupRules.appGroups);
            UUID groupId = matched != null ? matched.groupId : null;
            String groupName = matched != null ? matched.groupName : "Другие";
            String color = matched != null ? matched.color : "#9E9E9E";

            appGroupMap.computeIfAbsent(groupId, k -> new AppGroupAccumulator(groupId, groupName, color))
                    .addApp(row.processName, row.totalDurationMs, !row.sessionIds.isEmpty());
        }

        for (AppGroupAccumulator acc : appGroupMap.values()) {
            result.add(TimelineAppGroup.builder()
                    .groupId(acc.groupId)
                    .groupName(acc.groupName)
                    .color(acc.color)
                    .durationMs(acc.totalDuration)
                    .isBrowserGroup(false)
                    .apps(acc.toApps())
                    .siteGroups(null)
                    .build());
        }

        // --- Browser rows: group into a single "Browsers" app group, then sub-group by site groups ---
        if (!browserRows.isEmpty()) {
            long browserTotalDuration = browserRows.stream().mapToLong(r -> r.totalDurationMs).sum();

            // Group domains by site groups
            Map<UUID, SiteGroupAccumulator> siteGroupMap = new LinkedHashMap<>();

            for (FocusRow row : browserRows) {
                if (row.domain == null || row.domain.isBlank()) continue;

                AppGroupWithItems matched = matchSiteGroup(row.domain, groupRules.siteGroups);
                UUID groupId = matched != null ? matched.groupId : null;
                String groupName = matched != null ? matched.groupName : "Другие сайты";
                String color = matched != null ? matched.color : "#9E9E9E";

                siteGroupMap.computeIfAbsent(groupId, k -> new SiteGroupAccumulator(groupId, groupName, color))
                        .addSite(row.domain, row.totalDurationMs, !row.sessionIds.isEmpty());
            }

            List<TimelineSiteGroup> siteGroups = siteGroupMap.values().stream()
                    .map(SiteGroupAccumulator::toSiteGroup)
                    .toList();

            result.add(TimelineAppGroup.builder()
                    .groupId(null)
                    .groupName("Браузеры")
                    .color("#2196F3")
                    .durationMs(browserTotalDuration)
                    .isBrowserGroup(true)
                    .apps(null)
                    .siteGroups(siteGroups)
                    .build());
        }

        // Sort by duration desc
        result.sort(Comparator.comparingLong(TimelineAppGroup::getDurationMs).reversed());

        return result;
    }

    // ============================ Matching ============================

    private AppGroupWithItems matchAppGroup(String processName, List<AppGroupWithItems> groups) {
        for (AppGroupWithItems group : groups) {
            for (AppGroupItem item : group.items) {
                if (item.getItemType() != AppGroupItem.ItemType.APP) continue;
                if (matchesPattern(processName, item.getPattern(), item.getMatchType())) {
                    return group;
                }
            }
        }
        return null;
    }

    private AppGroupWithItems matchSiteGroup(String domain, List<AppGroupWithItems> groups) {
        for (AppGroupWithItems group : groups) {
            for (AppGroupItem item : group.items) {
                if (item.getItemType() != AppGroupItem.ItemType.SITE) continue;
                if (matchesPattern(domain, item.getPattern(), item.getMatchType())) {
                    return group;
                }
            }
        }
        return null;
    }

    private boolean matchesPattern(String value, String pattern, AppGroupItem.MatchType matchType) {
        if (value == null || pattern == null) return false;
        return switch (matchType) {
            case EXACT -> value.equalsIgnoreCase(pattern);
            case SUFFIX -> value.toLowerCase().endsWith(pattern.toLowerCase());
            case CONTAINS -> value.toLowerCase().contains(pattern.toLowerCase());
        };
    }

    // ============================ Internal records/classes ============================

    private record FocusRow(
            String username, int hour, String processName,
            boolean isBrowser, String domain, long totalDurationMs,
            List<UUID> deviceIds, List<UUID> sessionIds) {}

    private record UserInfo(String username, String displayName, List<UUID> deviceIds) {}

    private record GroupRules(List<AppGroupWithItems> appGroups, List<AppGroupWithItems> siteGroups) {}

    private record AppGroupWithItems(UUID groupId, String groupName, String color, List<AppGroupItem> items) {}

    private record CachedGroups(GroupRules rules, long expiresAt) {}

    // Accumulators for building grouped results
    private static class AppGroupAccumulator {
        final UUID groupId;
        final String groupName;
        final String color;
        long totalDuration;
        final Map<String, AppAccumulator> apps = new LinkedHashMap<>();

        AppGroupAccumulator(UUID groupId, String groupName, String color) {
            this.groupId = groupId;
            this.groupName = groupName;
            this.color = color;
        }

        void addApp(String processName, long durationMs, boolean hasRecording) {
            apps.computeIfAbsent(processName, k -> new AppAccumulator(processName))
                    .add(durationMs, hasRecording);
            totalDuration += durationMs;
        }

        List<TimelineApp> toApps() {
            return apps.values().stream()
                    .sorted(Comparator.comparingLong(a -> -a.durationMs))
                    .map(a -> TimelineApp.builder()
                            .processName(a.processName)
                            .durationMs(a.durationMs)
                            .hasRecording(a.hasRecording)
                            .build())
                    .toList();
        }
    }

    private static class AppAccumulator {
        final String processName;
        long durationMs;
        boolean hasRecording;

        AppAccumulator(String processName) {
            this.processName = processName;
        }

        void add(long dur, boolean rec) {
            this.durationMs += dur;
            if (rec) this.hasRecording = true;
        }
    }

    private static class SiteGroupAccumulator {
        final UUID groupId;
        final String groupName;
        final String color;
        long totalDuration;
        final Map<String, SiteAccumulator> sites = new LinkedHashMap<>();

        SiteGroupAccumulator(UUID groupId, String groupName, String color) {
            this.groupId = groupId;
            this.groupName = groupName;
            this.color = color;
        }

        void addSite(String domain, long durationMs, boolean hasRecording) {
            sites.computeIfAbsent(domain, k -> new SiteAccumulator(domain))
                    .add(durationMs, hasRecording);
            totalDuration += durationMs;
        }

        TimelineSiteGroup toSiteGroup() {
            List<TimelineSite> siteList = sites.values().stream()
                    .sorted(Comparator.comparingLong(s -> -s.durationMs))
                    .map(s -> TimelineSite.builder()
                            .domain(s.domain)
                            .durationMs(s.durationMs)
                            .hasRecording(s.hasRecording)
                            .build())
                    .toList();

            return TimelineSiteGroup.builder()
                    .groupId(groupId)
                    .groupName(groupName)
                    .color(color)
                    .durationMs(totalDuration)
                    .sites(siteList)
                    .build();
        }
    }

    private static class SiteAccumulator {
        final String domain;
        long durationMs;
        boolean hasRecording;

        SiteAccumulator(String domain) {
            this.domain = domain;
        }

        void add(long dur, boolean rec) {
            this.durationMs += dur;
            if (rec) this.hasRecording = true;
        }
    }

    // ============================ Utility ============================

    @SuppressWarnings("unchecked")
    private static List<UUID> parseUuidArray(Object value) {
        if (value == null) return List.of();
        if (value instanceof UUID[] arr) return Arrays.asList(arr);
        if (value instanceof Object[] arr) {
            return Arrays.stream(arr)
                    .filter(Objects::nonNull)
                    .map(o -> o instanceof UUID u ? u : UUID.fromString(o.toString()))
                    .collect(Collectors.toList());
        }
        if (value instanceof java.sql.Array sqlArray) {
            try {
                Object[] arr = (Object[]) sqlArray.getArray();
                return Arrays.stream(arr)
                        .filter(Objects::nonNull)
                        .map(o -> o instanceof UUID u ? u : UUID.fromString(o.toString()))
                        .collect(Collectors.toList());
            } catch (Exception e) {
                return List.of();
            }
        }
        return List.of();
    }

    private static UUID toUuid(Object value) {
        if (value == null) return null;
        if (value instanceof UUID u) return u;
        return UUID.fromString(value.toString());
    }

    private static Instant toInstant(Object value) {
        if (value == null) return null;
        if (value instanceof Instant inst) return inst;
        if (value instanceof java.sql.Timestamp ts) return ts.toInstant();
        if (value instanceof java.time.OffsetDateTime odt) return odt.toInstant();
        return Instant.parse(value.toString());
    }
}
