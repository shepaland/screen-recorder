package com.prg.ingest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserActivityResponse {
    private String username;
    private String displayName;
    private PeriodRange period;
    private ActivitySummary summary;
    private List<TopApp> topApps;
    private List<TopDomain> topDomains;
    private List<DailyBreakdown> dailyBreakdown;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PeriodRange {
        private String from;
        private String to;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ActivitySummary {
        private long totalActiveMs;
        private int totalDaysActive;
        private long avgDailyActiveMs;
        private int totalSessions;
        private int totalFocusIntervals;
        private int uniqueApps;
        private int uniqueDomains;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TopApp {
        private String processName;
        private String windowTitleSample;
        private long totalDurationMs;
        private double percentage;
        private int intervalCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TopDomain {
        private String domain;
        private String browserName;
        private long totalDurationMs;
        private double percentage;
        private int visitCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DailyBreakdown {
        private String date;
        private long totalActiveMs;
        private String firstActivityTs;
        private String lastActivityTs;
    }
}
