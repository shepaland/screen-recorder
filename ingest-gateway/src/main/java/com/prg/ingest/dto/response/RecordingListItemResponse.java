package com.prg.ingest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecordingListItemResponse {

    private UUID id;
    private UUID deviceId;
    private String deviceHostname;
    private Boolean deviceDeleted;
    private String employeeName;
    private String status;
    private Instant startedTs;
    private Instant endedTs;
    private Integer segmentCount;
    private Long totalBytes;
    private Long totalDurationMs;
    private Map<String, Object> metadata;
}
