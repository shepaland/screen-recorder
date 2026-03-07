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
public class DomainsReportResponse {
    private String username;
    private UserActivityResponse.PeriodRange period;
    private long totalBrowserMs;
    private List<DomainItem> content;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DomainItem {
        private String domain;
        private String browserName;
        private long totalDurationMs;
        private double percentage;
        private int visitCount;
        private long avgVisitDurationMs;
        private String firstVisitTs;
        private String lastVisitTs;
    }
}
