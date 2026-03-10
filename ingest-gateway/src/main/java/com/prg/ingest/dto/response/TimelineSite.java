package com.prg.ingest.dto.response;

import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TimelineSite {

    private String domain;
    private long durationMs;
    private boolean hasRecording;
}
