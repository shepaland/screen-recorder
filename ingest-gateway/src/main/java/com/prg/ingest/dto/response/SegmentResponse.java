package com.prg.ingest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SegmentResponse {

    private UUID id;
    private Integer sequenceNum;
    private Integer durationMs;
    private Long sizeBytes;
    private String status;
    private String s3Key;
}
