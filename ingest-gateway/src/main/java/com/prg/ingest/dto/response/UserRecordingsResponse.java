package com.prg.ingest.dto.response;

import lombok.*;

import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserRecordingsResponse {
    private String username;
    private List<RecordingItem> content;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RecordingItem {
        private UUID id;
        private UUID deviceId;
        private String deviceHostname;
        private String status;
        private String startedTs;
        private String endedTs;
        private int segmentCount;
        private long totalBytes;
        private long totalDurationMs;
    }
}
