package com.prg.ingest.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserListResponse {
    private List<UserSummary> content;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserSummary {
        private String username;
        private String displayName;
        private String windowsDomain;
        private int deviceCount;
        private List<UUID> deviceIds;
        private Instant firstSeenTs;
        private Instant lastSeenTs;
        @JsonProperty("is_active")
        private boolean isActive;
        private List<String> groups;
    }
}
