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
public class TopUsersUngroupedResponse {
    private List<UserUngrouped> users;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserUngrouped {
        private String username;
        private String displayName;
        private long totalUngroupedDurationMs;
        private double ungroupedPercentage;
        private List<UngroupedItem> topUngroupedItems;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UngroupedItem {
        private String name;
        private String displayName;
        private long durationMs;
    }
}
