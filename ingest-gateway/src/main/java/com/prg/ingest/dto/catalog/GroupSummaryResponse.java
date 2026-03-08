package com.prg.ingest.dto.catalog;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroupSummaryResponse {
    private long totalDurationMs;
    private List<GroupTotal> groups;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GroupTotal {
        private UUID groupId;
        private String groupName;
        private String color;
        private long durationMs;
        private double percentage;
        private int userCount;
    }
}
