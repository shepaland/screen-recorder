package com.prg.ingest.dto.response;

import lombok.*;

import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceDaysResponse {
    private UUID deviceId;
    private String deviceHostname;
    private String timezone;
    private List<RecordingDayResponse> days;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;
}
