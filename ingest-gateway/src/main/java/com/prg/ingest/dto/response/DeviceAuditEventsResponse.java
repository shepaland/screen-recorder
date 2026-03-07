package com.prg.ingest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceAuditEventsResponse {
    private UUID deviceId;
    private String date;
    private String timezone;
    private long totalElements;
    private List<AuditEventResponse> events;
}
