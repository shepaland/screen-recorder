package com.prg.ingest.dto.catalog;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroupItemResponse {
    private UUID id;
    private UUID groupId;
    private String itemType;
    private String pattern;
    private String matchType;
    private Instant createdAt;
}
