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
public class AppsReportResponse {
    private String username;
    private UserActivityResponse.PeriodRange period;
    private long totalActiveMs;
    private long realActiveMs;
    private List<AppItem> content;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AppItem {
        private String processName;
        private String windowTitleSample;
        private long totalDurationMs;
        private double percentage;
        private int intervalCount;
    }
}
