package com.prg.ingest.dto.response;

import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TimelineApp {

    private String processName;
    private long durationMs;
    private boolean hasRecording;
}
