package com.prg.auth.dto.response;

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
public class TenantResponse {
    private UUID id;
    private String name;
    private String slug;
    private Boolean isActive;
    private Map<String, Object> settings;
    private UUID adminUserId;
    private Instant createdTs;
    private Instant updatedTs;
}
