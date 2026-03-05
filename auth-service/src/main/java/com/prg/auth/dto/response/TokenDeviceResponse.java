package com.prg.auth.dto.response;

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
public class TokenDeviceResponse {

    private UUID tokenId;
    private String tokenName;
    private List<TokenDeviceItem> devices;
    private int totalCount;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TokenDeviceItem {
        private UUID id;
        private String hostname;
        private String osInfo;
        private String status;
        private Boolean isActive;
        private Boolean isDeleted;
        private Instant lastHeartbeatTs;
        private Instant createdTs;
    }
}
