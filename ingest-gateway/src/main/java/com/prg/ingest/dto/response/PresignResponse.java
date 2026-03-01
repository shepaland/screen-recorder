package com.prg.ingest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PresignResponse {

    private UUID segmentId;
    private String uploadUrl;
    private String uploadMethod;
    private Map<String, String> uploadHeaders;
    private Integer expiresInSec;
}
