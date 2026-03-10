package com.prg.ingest.dto.response;

import lombok.*;

import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TimelineSiteGroup {

    private UUID groupId;
    private String groupName;
    private String color;
    private long durationMs;
    private List<TimelineSite> sites;
}
