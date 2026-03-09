package com.prg.ingest.dto.response;

import lombok.*;

import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TimelineHour {

    private int hour;
    private long totalDurationMs;
    private boolean hasRecording;
    private List<UUID> recordingSessionIds;
    private List<TimelineAppGroup> appGroups;
}
