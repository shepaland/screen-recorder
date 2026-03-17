package com.prg.search.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SegmentSearchResult {
    private String segmentId;
    private String tenantId;
    private String deviceId;
    private String sessionId;
    private Integer sequenceNum;
    private String s3Key;
    private Long sizeBytes;
    private Integer durationMs;
    private String checksumSha256;
    private String timestamp;
    private Map<String, Object> metadata;
}
