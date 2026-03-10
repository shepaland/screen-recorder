package com.prg.auth.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
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
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DeviceTokenResponse {
    private UUID id;
    private String token;
    private String tokenPreview;
    private String name;
    private Integer maxUses;
    private Integer currentUses;
    private Integer deviceCount;
    private Instant expiresAt;
    private Boolean isActive;
    private Boolean recordingEnabled;
    private String createdByUsername;
    private Instant createdTs;
}
