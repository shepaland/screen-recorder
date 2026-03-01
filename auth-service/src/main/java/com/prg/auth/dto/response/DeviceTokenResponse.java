package com.prg.auth.dto.response;

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
public class DeviceTokenResponse {
    private UUID id;
    private String token;
    private String name;
    private Integer maxUses;
    private Integer currentUses;
    private Instant expiresAt;
    private Boolean isActive;
    private Instant createdTs;
}
