package com.prg.ingest.service;

import com.prg.ingest.dto.response.*;
import com.prg.ingest.dto.response.UserActivityResponse.*;
import com.prg.ingest.repository.DeviceUserSessionRepository;
import com.prg.ingest.security.DevicePrincipal;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserActivityService {

    private final DeviceUserSessionRepository sessionRepo;
    private final EntityManager em;

    // Whitelist for sort columns to prevent SQL injection
    private static final Map<String, String> ALLOWED_USER_SORT = Map.of(
            "username", "username",
            "last_seen_ts", "last_seen_ts",
            "first_seen_ts", "first_seen_ts",
            "device_count", "device_count"
    );
    private static final Set<String> ALLOWED_SORT_DIR = Set.of("asc", "desc");

    @Transactional(readOnly = true)
    public UserListResponse getUsers(UUID tenantId, int page, int size,
                                     String search, String sortBy, String sortDir,
                                     Boolean isActive) {
        // Validate sort params via whitelist
        String safeSortColumn = ALLOWED_USER_SORT.getOrDefault(sortBy, "last_seen_ts");
        String safeSortDir = ALLOWED_SORT_DIR.contains(sortDir) ? sortDir : "desc";

        // Clamp page/size
        if (page < 0) page = 0;
        if (size < 1) size = 1;
        if (size > 100) size = 100;

        StringBuilder sql = new StringBuilder("""
                SELECT username, display_name, windows_domain,
                       device_count, device_ids, first_seen_ts, last_seen_ts, is_active
                FROM v_tenant_users
                WHERE (CAST(:tenantId AS uuid) IS NULL OR tenant_id = :tenantId)
                """);

        StringBuilder countSql = new StringBuilder("""
                SELECT COUNT(*)
                FROM v_tenant_users
                WHERE (CAST(:tenantId AS uuid) IS NULL OR tenant_id = :tenantId)
                """);

        if (search != null && !search.isBlank()) {
            String clause = " AND (username ILIKE :search OR display_name ILIKE :search)";
            sql.append(clause);
            countSql.append(clause);
        }

        if (isActive != null) {
            String clause = " AND is_active = :isActive";
            sql.append(clause);
            countSql.append(clause);
        }

        sql.append(" ORDER BY ").append(safeSortColumn).append(" ").append(safeSortDir);
        sql.append(" LIMIT :lim OFFSET :off");

        var query = em.createNativeQuery(sql.toString())
                .setParameter("tenantId", tenantId)
                .setParameter("lim", size)
                .setParameter("off", page * size);

        var countQuery = em.createNativeQuery(countSql.toString())
                .setParameter("tenantId", tenantId);

        if (search != null && !search.isBlank()) {
            String searchPattern = "%" + search.replace("%", "\\%").replace("_", "\\_") + "%";
            query.setParameter("search", searchPattern);
            countQuery.setParameter("search", searchPattern);
        }

        if (isActive != null) {
            query.setParameter("isActive", isActive);
            countQuery.setParameter("isActive", isActive);
        }

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        long totalElements = ((Number) countQuery.getSingleResult()).longValue();
        int totalPages = (int) Math.ceil((double) totalElements / size);

        List<UserListResponse.UserSummary> users = rows.stream()
                .map(row -> UserListResponse.UserSummary.builder()
                        .username((String) row[0])
                        .displayName((String) row[1])
                        .windowsDomain((String) row[2])
                        .deviceCount(((Number) row[3]).intValue())
                        .deviceIds(parseUuidArray(row[4]))
                        .firstSeenTs(toInstant(row[5]))
                        .lastSeenTs(toInstant(row[6]))
                        .isActive(row[7] != null && (Boolean) row[7])
                        .build())
                .toList();

        return UserListResponse.builder()
                .content(users)
                .page(page)
                .size(size)
                .totalElements(totalElements)
                .totalPages(totalPages)
                .build();
    }

    @Transactional(readOnly = true)
    public UserActivityResponse getUserActivity(UUID tenantId, String username,
                                                 String from, String to, UUID deviceId) {
        // Get user display name
        String displayName = getDisplayName(tenantId, username);

        // Summary query
        StringBuilder summarySql = new StringBuilder("""
                SELECT COUNT(DISTINCT DATE(started_at)),
                       COUNT(*),
                       SUM(duration_ms),
                       COUNT(DISTINCT process_name),
                       COUNT(DISTINCT CASE WHEN is_browser = true THEN domain END)
                FROM app_focus_intervals
                WHERE (CAST(:tenantId AS uuid) IS NULL OR tenant_id = :tenantId) AND username = :username
                  AND started_at >= CAST(:from AS timestamptz)
                  AND started_at < CAST(:to AS timestamptz) + INTERVAL '1 day'
                """);

        if (deviceId != null) {
            summarySql.append(" AND device_id = :deviceId");
        }

        var summaryQuery = em.createNativeQuery(summarySql.toString())
                .setParameter("tenantId", tenantId)
                .setParameter("username", username)
                .setParameter("from", from)
                .setParameter("to", to);
        if (deviceId != null) summaryQuery.setParameter("deviceId", deviceId);

        Object[] summaryRow = (Object[]) summaryQuery.getSingleResult();
        int daysActive = ((Number) summaryRow[0]).intValue();
        int totalIntervals = ((Number) summaryRow[1]).intValue();
        long totalActiveMs = summaryRow[2] != null ? ((Number) summaryRow[2]).longValue() : 0;
        int uniqueApps = ((Number) summaryRow[3]).intValue();
        int uniqueDomains = ((Number) summaryRow[4]).intValue();

        // Session count
        StringBuilder sessionCountSql = new StringBuilder("""
                SELECT COUNT(DISTINCT session_id)
                FROM app_focus_intervals
                WHERE (CAST(:tenantId AS uuid) IS NULL OR tenant_id = :tenantId) AND username = :username
                  AND started_at >= CAST(:from AS timestamptz)
                  AND started_at < CAST(:to AS timestamptz) + INTERVAL '1 day'
                  AND session_id IS NOT NULL
                """);
        if (deviceId != null) sessionCountSql.append(" AND device_id = :deviceId");

        var sessionQuery = em.createNativeQuery(sessionCountSql.toString())
                .setParameter("tenantId", tenantId)
                .setParameter("username", username)
                .setParameter("from", from)
                .setParameter("to", to);
        if (deviceId != null) sessionQuery.setParameter("deviceId", deviceId);
        int totalSessions = ((Number) sessionQuery.getSingleResult()).intValue();

        // Top apps
        StringBuilder topAppsSql = new StringBuilder("""
                SELECT process_name,
                       (ARRAY_AGG(window_title ORDER BY started_at DESC))[1] AS window_title_sample,
                       SUM(duration_ms) AS total_duration,
                       COUNT(*) AS cnt
                FROM app_focus_intervals
                WHERE (CAST(:tenantId AS uuid) IS NULL OR tenant_id = :tenantId) AND username = :username
                  AND started_at >= CAST(:from AS timestamptz)
                  AND started_at < CAST(:to AS timestamptz) + INTERVAL '1 day'
                """);
        if (deviceId != null) topAppsSql.append(" AND device_id = :deviceId");
        topAppsSql.append(" GROUP BY process_name ORDER BY total_duration DESC LIMIT 10");

        var topAppsQuery = em.createNativeQuery(topAppsSql.toString())
                .setParameter("tenantId", tenantId)
                .setParameter("username", username)
                .setParameter("from", from)
                .setParameter("to", to);
        if (deviceId != null) topAppsQuery.setParameter("deviceId", deviceId);

        @SuppressWarnings("unchecked")
        List<Object[]> topAppsRows = topAppsQuery.getResultList();
        List<TopApp> topApps = topAppsRows.stream()
                .map(r -> TopApp.builder()
                        .processName((String) r[0])
                        .windowTitleSample((String) r[1])
                        .totalDurationMs(((Number) r[2]).longValue())
                        .percentage(totalActiveMs > 0 ? (((Number) r[2]).longValue() * 100.0 / totalActiveMs) : 0)
                        .intervalCount(((Number) r[3]).intValue())
                        .build())
                .toList();

        // Top domains
        StringBuilder topDomainsSql = new StringBuilder("""
                SELECT domain, browser_name,
                       SUM(duration_ms) AS total_duration,
                       COUNT(*) AS cnt
                FROM app_focus_intervals
                WHERE (CAST(:tenantId AS uuid) IS NULL OR tenant_id = :tenantId) AND username = :username
                  AND is_browser = true AND domain IS NOT NULL
                  AND started_at >= CAST(:from AS timestamptz)
                  AND started_at < CAST(:to AS timestamptz) + INTERVAL '1 day'
                """);
        if (deviceId != null) topDomainsSql.append(" AND device_id = :deviceId");
        topDomainsSql.append(" GROUP BY domain, browser_name ORDER BY total_duration DESC LIMIT 10");

        var topDomainsQuery = em.createNativeQuery(topDomainsSql.toString())
                .setParameter("tenantId", tenantId)
                .setParameter("username", username)
                .setParameter("from", from)
                .setParameter("to", to);
        if (deviceId != null) topDomainsQuery.setParameter("deviceId", deviceId);

        @SuppressWarnings("unchecked")
        List<Object[]> topDomainRows = topDomainsQuery.getResultList();
        long totalBrowserMs = topDomainRows.stream().mapToLong(r -> ((Number) r[2]).longValue()).sum();
        List<TopDomain> topDomains = topDomainRows.stream()
                .map(r -> TopDomain.builder()
                        .domain((String) r[0])
                        .browserName((String) r[1])
                        .totalDurationMs(((Number) r[2]).longValue())
                        .percentage(totalBrowserMs > 0 ? (((Number) r[2]).longValue() * 100.0 / totalBrowserMs) : 0)
                        .visitCount(((Number) r[3]).intValue())
                        .build())
                .toList();

        // Daily breakdown
        StringBuilder dailySql = new StringBuilder("""
                SELECT DATE(started_at) AS d,
                       SUM(duration_ms),
                       MIN(started_at),
                       MAX(COALESCE(ended_at, started_at))
                FROM app_focus_intervals
                WHERE (CAST(:tenantId AS uuid) IS NULL OR tenant_id = :tenantId) AND username = :username
                  AND started_at >= CAST(:from AS timestamptz)
                  AND started_at < CAST(:to AS timestamptz) + INTERVAL '1 day'
                """);
        if (deviceId != null) dailySql.append(" AND device_id = :deviceId");
        dailySql.append(" GROUP BY d ORDER BY d");

        var dailyQuery = em.createNativeQuery(dailySql.toString())
                .setParameter("tenantId", tenantId)
                .setParameter("username", username)
                .setParameter("from", from)
                .setParameter("to", to);
        if (deviceId != null) dailyQuery.setParameter("deviceId", deviceId);

        @SuppressWarnings("unchecked")
        List<Object[]> dailyRows = dailyQuery.getResultList();
        List<DailyBreakdown> dailyBreakdown = dailyRows.stream()
                .map(r -> DailyBreakdown.builder()
                        .date(r[0].toString())
                        .totalActiveMs(((Number) r[1]).longValue())
                        .firstActivityTs(r[2] != null ? toInstant(r[2]).toString() : null)
                        .lastActivityTs(r[3] != null ? toInstant(r[3]).toString() : null)
                        .build())
                .toList();

        return UserActivityResponse.builder()
                .username(username)
                .displayName(displayName)
                .period(PeriodRange.builder().from(from).to(to).build())
                .summary(ActivitySummary.builder()
                        .totalActiveMs(totalActiveMs)
                        .totalDaysActive(daysActive)
                        .avgDailyActiveMs(daysActive > 0 ? totalActiveMs / daysActive : 0)
                        .totalSessions(totalSessions)
                        .totalFocusIntervals(totalIntervals)
                        .uniqueApps(uniqueApps)
                        .uniqueDomains(uniqueDomains)
                        .build())
                .topApps(topApps)
                .topDomains(topDomains)
                .dailyBreakdown(dailyBreakdown)
                .build();
    }

    @Transactional(readOnly = true)
    public AppsReportResponse getUserApps(UUID tenantId, String username,
                                           String from, String to,
                                           int page, int size,
                                           String sortBy, String sortDir,
                                           UUID deviceId) {
        if (page < 0) page = 0;
        if (size < 1) size = 1;
        if (size > 100) size = 100;

        // Total active time
        StringBuilder totalSql = new StringBuilder("""
                SELECT COALESCE(SUM(duration_ms), 0)
                FROM app_focus_intervals
                WHERE (CAST(:tenantId AS uuid) IS NULL OR tenant_id = :tenantId) AND username = :username
                  AND started_at >= CAST(:from AS timestamptz)
                  AND started_at < CAST(:to AS timestamptz) + INTERVAL '1 day'
                """);
        if (deviceId != null) totalSql.append(" AND device_id = :deviceId");

        var totalQuery = em.createNativeQuery(totalSql.toString())
                .setParameter("tenantId", tenantId)
                .setParameter("username", username)
                .setParameter("from", from)
                .setParameter("to", to);
        if (deviceId != null) totalQuery.setParameter("deviceId", deviceId);
        long totalActiveMs = ((Number) totalQuery.getSingleResult()).longValue();

        // Paginated apps
        String safeSortCol = "total_duration"; // only allowed sort
        String safeSortDir = ALLOWED_SORT_DIR.contains(sortDir) ? sortDir : "desc";

        StringBuilder appsSql = new StringBuilder("""
                SELECT process_name,
                       (ARRAY_AGG(window_title ORDER BY started_at DESC))[1] AS window_title_sample,
                       SUM(duration_ms) AS total_duration,
                       COUNT(*) AS cnt
                FROM app_focus_intervals
                WHERE (CAST(:tenantId AS uuid) IS NULL OR tenant_id = :tenantId) AND username = :username
                  AND started_at >= CAST(:from AS timestamptz)
                  AND started_at < CAST(:to AS timestamptz) + INTERVAL '1 day'
                """);
        if (deviceId != null) appsSql.append(" AND device_id = :deviceId");
        appsSql.append(" GROUP BY process_name ORDER BY ").append(safeSortCol).append(" ").append(safeSortDir);
        appsSql.append(" LIMIT :lim OFFSET :off");

        var appsQuery = em.createNativeQuery(appsSql.toString())
                .setParameter("tenantId", tenantId)
                .setParameter("username", username)
                .setParameter("from", from)
                .setParameter("to", to)
                .setParameter("lim", size)
                .setParameter("off", page * size);
        if (deviceId != null) appsQuery.setParameter("deviceId", deviceId);

        // Count distinct apps
        StringBuilder countSql = new StringBuilder("""
                SELECT COUNT(DISTINCT process_name)
                FROM app_focus_intervals
                WHERE (CAST(:tenantId AS uuid) IS NULL OR tenant_id = :tenantId) AND username = :username
                  AND started_at >= CAST(:from AS timestamptz)
                  AND started_at < CAST(:to AS timestamptz) + INTERVAL '1 day'
                """);
        if (deviceId != null) countSql.append(" AND device_id = :deviceId");

        var countQuery = em.createNativeQuery(countSql.toString())
                .setParameter("tenantId", tenantId)
                .setParameter("username", username)
                .setParameter("from", from)
                .setParameter("to", to);
        if (deviceId != null) countQuery.setParameter("deviceId", deviceId);
        long totalElements = ((Number) countQuery.getSingleResult()).longValue();

        @SuppressWarnings("unchecked")
        List<Object[]> rows = appsQuery.getResultList();
        List<AppsReportResponse.AppItem> items = rows.stream()
                .map(r -> AppsReportResponse.AppItem.builder()
                        .processName((String) r[0])
                        .windowTitleSample((String) r[1])
                        .totalDurationMs(((Number) r[2]).longValue())
                        .percentage(totalActiveMs > 0 ? (((Number) r[2]).longValue() * 100.0 / totalActiveMs) : 0)
                        .intervalCount(((Number) r[3]).intValue())
                        .build())
                .toList();

        return AppsReportResponse.builder()
                .username(username)
                .period(PeriodRange.builder().from(from).to(to).build())
                .totalActiveMs(totalActiveMs)
                .content(items)
                .page(page)
                .size(size)
                .totalElements(totalElements)
                .totalPages((int) Math.ceil((double) totalElements / size))
                .build();
    }

    @Transactional(readOnly = true)
    public DomainsReportResponse getUserDomains(UUID tenantId, String username,
                                                 String from, String to,
                                                 int page, int size, UUID deviceId) {
        if (page < 0) page = 0;
        if (size < 1) size = 1;
        if (size > 100) size = 100;

        // Total browser time
        StringBuilder totalSql = new StringBuilder("""
                SELECT COALESCE(SUM(duration_ms), 0)
                FROM app_focus_intervals
                WHERE (CAST(:tenantId AS uuid) IS NULL OR tenant_id = :tenantId) AND username = :username AND is_browser = true
                  AND started_at >= CAST(:from AS timestamptz)
                  AND started_at < CAST(:to AS timestamptz) + INTERVAL '1 day'
                """);
        if (deviceId != null) totalSql.append(" AND device_id = :deviceId");

        var totalQuery = em.createNativeQuery(totalSql.toString())
                .setParameter("tenantId", tenantId)
                .setParameter("username", username)
                .setParameter("from", from)
                .setParameter("to", to);
        if (deviceId != null) totalQuery.setParameter("deviceId", deviceId);
        long totalBrowserMs = ((Number) totalQuery.getSingleResult()).longValue();

        // Paginated domains
        StringBuilder domainsSql = new StringBuilder("""
                SELECT domain, browser_name,
                       SUM(duration_ms) AS total_duration,
                       COUNT(*) AS cnt,
                       AVG(duration_ms) AS avg_dur,
                       MIN(started_at) AS first_visit,
                       MAX(started_at) AS last_visit
                FROM app_focus_intervals
                WHERE (CAST(:tenantId AS uuid) IS NULL OR tenant_id = :tenantId) AND username = :username
                  AND is_browser = true AND domain IS NOT NULL
                  AND started_at >= CAST(:from AS timestamptz)
                  AND started_at < CAST(:to AS timestamptz) + INTERVAL '1 day'
                """);
        if (deviceId != null) domainsSql.append(" AND device_id = :deviceId");
        domainsSql.append(" GROUP BY domain, browser_name ORDER BY total_duration DESC");
        domainsSql.append(" LIMIT :lim OFFSET :off");

        var domainsQuery = em.createNativeQuery(domainsSql.toString())
                .setParameter("tenantId", tenantId)
                .setParameter("username", username)
                .setParameter("from", from)
                .setParameter("to", to)
                .setParameter("lim", size)
                .setParameter("off", page * size);
        if (deviceId != null) domainsQuery.setParameter("deviceId", deviceId);

        // Count distinct domains
        StringBuilder countSql = new StringBuilder("""
                SELECT COUNT(DISTINCT domain)
                FROM app_focus_intervals
                WHERE (CAST(:tenantId AS uuid) IS NULL OR tenant_id = :tenantId) AND username = :username AND is_browser = true AND domain IS NOT NULL
                  AND started_at >= CAST(:from AS timestamptz)
                  AND started_at < CAST(:to AS timestamptz) + INTERVAL '1 day'
                """);
        if (deviceId != null) countSql.append(" AND device_id = :deviceId");

        var countQuery = em.createNativeQuery(countSql.toString())
                .setParameter("tenantId", tenantId)
                .setParameter("username", username)
                .setParameter("from", from)
                .setParameter("to", to);
        if (deviceId != null) countQuery.setParameter("deviceId", deviceId);
        long totalElements = ((Number) countQuery.getSingleResult()).longValue();

        @SuppressWarnings("unchecked")
        List<Object[]> rows = domainsQuery.getResultList();
        List<DomainsReportResponse.DomainItem> items = rows.stream()
                .map(r -> DomainsReportResponse.DomainItem.builder()
                        .domain((String) r[0])
                        .browserName((String) r[1])
                        .totalDurationMs(((Number) r[2]).longValue())
                        .percentage(totalBrowserMs > 0 ? (((Number) r[2]).longValue() * 100.0 / totalBrowserMs) : 0)
                        .visitCount(((Number) r[3]).intValue())
                        .avgVisitDurationMs(((Number) r[4]).longValue())
                        .firstVisitTs(r[5] != null ? toInstant(r[5]).toString() : null)
                        .lastVisitTs(r[6] != null ? toInstant(r[6]).toString() : null)
                        .build())
                .toList();

        return DomainsReportResponse.builder()
                .username(username)
                .period(PeriodRange.builder().from(from).to(to).build())
                .totalBrowserMs(totalBrowserMs)
                .content(items)
                .page(page)
                .size(size)
                .totalElements(totalElements)
                .totalPages((int) Math.ceil((double) totalElements / size))
                .build();
    }

    @Transactional(readOnly = true)
    public WorktimeResponse getUserWorktime(UUID tenantId, String username,
                                             String from, String to,
                                             String timezone, UUID deviceId) {
        ZoneId zoneId = ZoneId.of(timezone);
        LocalDate fromDate = LocalDate.parse(from);
        LocalDate toDate = LocalDate.parse(to);

        // Daily activity: first/last activity, total duration
        StringBuilder dailySql = new StringBuilder("""
                SELECT DATE(started_at AT TIME ZONE :tz) AS d,
                       MIN(started_at AT TIME ZONE :tz),
                       MAX(COALESCE(ended_at, started_at) AT TIME ZONE :tz),
                       SUM(duration_ms)
                FROM app_focus_intervals
                WHERE (CAST(:tenantId AS uuid) IS NULL OR tenant_id = :tenantId) AND username = :username
                  AND started_at >= CAST(:from AS timestamp) AT TIME ZONE :tz
                  AND started_at < (CAST(:to AS timestamp) + INTERVAL '1 day') AT TIME ZONE :tz
                """);
        if (deviceId != null) dailySql.append(" AND device_id = :deviceId");
        dailySql.append(" GROUP BY d ORDER BY d");

        var dailyQuery = em.createNativeQuery(dailySql.toString())
                .setParameter("tenantId", tenantId)
                .setParameter("username", username)
                .setParameter("from", from)
                .setParameter("to", to)
                .setParameter("tz", timezone);
        if (deviceId != null) dailyQuery.setParameter("deviceId", deviceId);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = dailyQuery.getResultList();

        Map<LocalDate, Object[]> dayMap = new HashMap<>();
        for (Object[] row : rows) {
            LocalDate d = LocalDate.parse(row[0].toString());
            dayMap.put(d, row);
        }

        DateTimeFormatter timeFormat = DateTimeFormatter.ofPattern("HH:mm");
        List<WorktimeResponse.WorktimeDay> days = new ArrayList<>();
        LocalTime workStart = LocalTime.of(9, 0);
        LocalTime workEnd = LocalTime.of(18, 0);
        double workHours = 9.0;

        for (LocalDate d = fromDate; !d.isAfter(toDate); d = d.plusDays(1)) {
            DayOfWeek dow = d.getDayOfWeek();
            boolean isWorkday = dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY;
            Object[] data = dayMap.get(d);

            if (data == null) {
                days.add(WorktimeResponse.WorktimeDay.builder()
                        .date(d.toString())
                        .weekday(dow.toString().substring(0, 3).toLowerCase())
                        .isWorkday(isWorkday)
                        .status(isWorkday ? "absent" : "weekend")
                        .totalHours(0)
                        .activeHours(0)
                        .isLate(false)
                        .isEarlyLeave(false)
                        .overtimeHours(0)
                        .build());
            } else {
                java.sql.Timestamp arrival = (java.sql.Timestamp) data[1];
                java.sql.Timestamp departure = (java.sql.Timestamp) data[2];
                long totalMs = ((Number) data[3]).longValue();
                double totalHrs = totalMs / 3600000.0;

                LocalTime arrivalTime = arrival.toLocalDateTime().toLocalTime();
                LocalTime departureTime = departure.toLocalDateTime().toLocalTime();

                boolean isLate = isWorkday && arrivalTime.isAfter(workStart.plusMinutes(15));
                boolean isEarlyLeave = isWorkday && departureTime.isBefore(workEnd.minusMinutes(15));

                String status;
                if (!isWorkday) {
                    status = "weekend";
                } else if (totalHrs >= workHours * 0.8) {
                    status = "present";
                } else if (totalHrs > 0) {
                    status = "partial";
                } else {
                    status = "absent";
                }

                double overtime = isWorkday && totalHrs > workHours ? totalHrs - workHours : 0;

                days.add(WorktimeResponse.WorktimeDay.builder()
                        .date(d.toString())
                        .weekday(dow.toString().substring(0, 3).toLowerCase())
                        .isWorkday(isWorkday)
                        .status(status)
                        .arrivalTime(arrivalTime.format(timeFormat))
                        .departureTime(departureTime.format(timeFormat))
                        .totalHours(Math.round(totalHrs * 100.0) / 100.0)
                        .activeHours(Math.round(totalHrs * 100.0) / 100.0)
                        .isLate(isLate)
                        .isEarlyLeave(isEarlyLeave)
                        .overtimeHours(Math.round(overtime * 100.0) / 100.0)
                        .build());
            }
        }

        return WorktimeResponse.builder()
                .username(username)
                .period(PeriodRange.builder().from(from).to(to).build())
                .workSchedule(WorktimeResponse.WorkSchedule.builder()
                        .start("09:00").end("18:00").timezone(timezone).build())
                .days(days)
                .build();
    }

    @Transactional(readOnly = true)
    public TimesheetResponse getUserTimesheet(UUID tenantId, String username,
                                               String month, String workStartStr,
                                               String workEndStr, String timezone,
                                               UUID deviceId) {
        String displayName = getDisplayName(tenantId, username);
        YearMonth ym = YearMonth.parse(month);
        LocalDate fromDate = ym.atDay(1);
        LocalDate toDate = ym.atEndOfMonth();

        LocalTime workStart = LocalTime.parse(workStartStr);
        LocalTime workEnd = LocalTime.parse(workEndStr);
        double dailyWorkHours = Duration.between(workStart, workEnd).toMinutes() / 60.0;

        // Get worktime data
        WorktimeResponse worktime = getUserWorktime(tenantId, username,
                fromDate.toString(), toDate.toString(), timezone, deviceId);

        int daysPresent = 0, daysAbsent = 0, daysPartial = 0;
        double totalActualHours = 0, totalOvertimeHours = 0;
        int workDaysInMonth = 0;
        List<String> arrivalTimes = new ArrayList<>();
        List<String> departureTimes = new ArrayList<>();

        List<TimesheetResponse.TimesheetDay> tsDays = new ArrayList<>();
        for (WorktimeResponse.WorktimeDay day : worktime.getDays()) {
            if (day.isWorkday()) workDaysInMonth++;

            switch (day.getStatus()) {
                case "present" -> daysPresent++;
                case "absent" -> { if (day.isWorkday()) daysAbsent++; }
                case "partial" -> daysPartial++;
            }

            totalActualHours += day.getTotalHours();
            totalOvertimeHours += day.getOvertimeHours();

            if (day.getArrivalTime() != null) arrivalTimes.add(day.getArrivalTime());
            if (day.getDepartureTime() != null) departureTimes.add(day.getDepartureTime());

            double idleHours = day.isWorkday() ?
                    Math.max(0, dailyWorkHours - day.getActiveHours()) : 0;

            tsDays.add(TimesheetResponse.TimesheetDay.builder()
                    .date(day.getDate())
                    .weekday(day.getWeekday())
                    .isWorkday(day.isWorkday())
                    .status(day.getStatus())
                    .arrivalTime(day.getArrivalTime())
                    .departureTime(day.getDepartureTime())
                    .totalHours(day.getTotalHours())
                    .activeHours(day.getActiveHours())
                    .idleHours(Math.round(idleHours * 100.0) / 100.0)
                    .isLate(day.isLate())
                    .isEarlyLeave(day.isEarlyLeave())
                    .overtimeHours(day.getOvertimeHours())
                    .build());
        }

        double totalExpectedHours = workDaysInMonth * dailyWorkHours;

        // Average arrival/departure
        String avgArrival = calculateAverageTime(arrivalTimes);
        String avgDeparture = calculateAverageTime(departureTimes);

        return TimesheetResponse.builder()
                .username(username)
                .displayName(displayName)
                .month(month)
                .workSchedule(WorktimeResponse.WorkSchedule.builder()
                        .start(workStartStr).end(workEndStr).timezone(timezone).build())
                .summary(TimesheetResponse.TimesheetSummary.builder()
                        .workDaysInMonth(workDaysInMonth)
                        .daysPresent(daysPresent)
                        .daysAbsent(daysAbsent)
                        .daysPartial(daysPartial)
                        .totalExpectedHours(Math.round(totalExpectedHours * 100.0) / 100.0)
                        .totalActualHours(Math.round(totalActualHours * 100.0) / 100.0)
                        .totalOvertimeHours(Math.round(totalOvertimeHours * 100.0) / 100.0)
                        .avgArrivalTime(avgArrival)
                        .avgDepartureTime(avgDeparture)
                        .build())
                .days(tsDays)
                .build();
    }

    // --- Helper methods ---

    private String getDisplayName(UUID tenantId, String username) {
        var query = em.createNativeQuery(
                "SELECT display_name FROM device_user_sessions WHERE (CAST(:tenantId AS uuid) IS NULL OR tenant_id = :tenantId) AND username = :username LIMIT 1")
                .setParameter("tenantId", tenantId)
                .setParameter("username", username);
        try {
            Object result = query.getSingleResult();
            return result != null ? result.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String calculateAverageTime(List<String> times) {
        if (times.isEmpty()) return null;
        long totalMinutes = 0;
        for (String t : times) {
            String[] parts = t.split(":");
            totalMinutes += Integer.parseInt(parts[0]) * 60L + Integer.parseInt(parts[1]);
        }
        long avg = totalMinutes / times.size();
        return String.format("%02d:%02d", avg / 60, avg % 60);
    }

    @SuppressWarnings("unchecked")
    private static List<UUID> parseUuidArray(Object value) {
        if (value == null) return List.of();
        if (value instanceof UUID[] arr) return Arrays.asList(arr);
        if (value instanceof Object[] arr) {
            return Arrays.stream(arr)
                    .map(o -> o instanceof UUID u ? u : UUID.fromString(o.toString()))
                    .collect(Collectors.toList());
        }
        if (value instanceof java.sql.Array sqlArray) {
            try {
                Object[] arr = (Object[]) sqlArray.getArray();
                return Arrays.stream(arr)
                        .map(o -> o instanceof UUID u ? u : UUID.fromString(o.toString()))
                        .collect(Collectors.toList());
            } catch (Exception e) {
                return List.of();
            }
        }
        return List.of();
    }

    private static java.time.Instant toInstant(Object value) {
        if (value == null) return null;
        if (value instanceof java.time.Instant inst) return inst;
        if (value instanceof java.sql.Timestamp ts) return ts.toInstant();
        if (value instanceof java.time.OffsetDateTime odt) return odt.toInstant();
        return java.time.Instant.parse(value.toString());
    }
}
