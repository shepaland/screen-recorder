package com.prg.ingest.dto.catalog;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardMetricsResponse {
    private long connectedDevices;
    private long totalDevices;
    private long activeUsers;
    private long tokensUsed;
    private long tokensTotal;
    private long videoSizeBytes;
}
