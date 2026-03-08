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
public class AliasResponse {
    private UUID id;
    private String aliasType;
    private String original;
    private String displayName;
    private String iconUrl;
    private Instant createdAt;
    private Instant updatedAt;
}
