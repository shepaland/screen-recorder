package com.prg.ingest.dto.response;

import lombok.*;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TimelineResponse {

    private String date;
    private String timezone;
    private List<TimelineUser> users;
}
