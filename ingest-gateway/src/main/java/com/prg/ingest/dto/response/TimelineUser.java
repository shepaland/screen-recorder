package com.prg.ingest.dto.response;

import lombok.*;

import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TimelineUser {

    private String username;
    private String displayName;
    private List<UUID> deviceIds;
    private List<TimelineHour> hours;
}
