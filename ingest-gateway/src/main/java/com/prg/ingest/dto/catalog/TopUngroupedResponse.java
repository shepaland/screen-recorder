package com.prg.ingest.dto.catalog;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TopUngroupedResponse {
    private List<UngroupedItem> items;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UngroupedItem {
        private String name;
        private String displayName;
        private long totalDurationMs;
        private double percentage;
        private int userCount;
    }
}
