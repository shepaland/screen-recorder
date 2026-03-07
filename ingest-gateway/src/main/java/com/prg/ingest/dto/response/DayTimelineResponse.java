package com.prg.ingest.dto.response;

import lombok.*;

import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DayTimelineResponse {
    private UUID deviceId;
    private String date;
    private String timezone;
    private List<TimelineSessionResponse> sessions;
}
