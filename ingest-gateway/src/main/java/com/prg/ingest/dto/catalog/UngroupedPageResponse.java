package com.prg.ingest.dto.catalog;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UngroupedPageResponse {

    private List<UngroupedItem> content;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;
    private long totalUngroupedDurationMs;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UngroupedItem {
        private String name;
        private String displayName;
        private long totalDurationMs;
        private int intervalCount;
        private int userCount;
        private Instant lastSeenAt;
    }
}
