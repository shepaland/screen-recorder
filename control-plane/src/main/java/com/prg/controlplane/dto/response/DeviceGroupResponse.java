package com.prg.controlplane.dto.response;

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
public class DeviceGroupResponse {
    private UUID id;
    private UUID parentId;
    private String name;
    private String description;
    private String color;
    private Integer sortOrder;
    private DeviceGroupStatsResponse stats;
    private Instant createdAt;
    private Instant updatedAt;
}
