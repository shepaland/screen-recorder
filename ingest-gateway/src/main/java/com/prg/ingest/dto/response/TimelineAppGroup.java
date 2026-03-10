package com.prg.ingest.dto.response;

import lombok.*;

import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TimelineAppGroup {

    private UUID groupId;
    private String groupName;
    private String color;
    private long durationMs;
    private boolean isBrowserGroup;
    private List<TimelineApp> apps;
    private List<TimelineSiteGroup> siteGroups;
}
