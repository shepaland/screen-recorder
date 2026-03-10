package com.prg.ingest.dto.catalog;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroupResponse {
    private UUID id;
    private String groupType;
    private String name;
    private String description;
    private String color;
    private int sortOrder;

    @JsonProperty("is_default")
    private boolean isDefault;

    @JsonProperty("is_browser_group")
    private boolean isBrowserGroup;

    private int itemCount;
    private List<GroupItemResponse> items;
    private Instant createdAt;
    private Instant updatedAt;
}
