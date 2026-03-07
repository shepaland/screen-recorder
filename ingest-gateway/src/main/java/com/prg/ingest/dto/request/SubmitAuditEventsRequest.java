package com.prg.ingest.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
public class SubmitAuditEventsRequest {

    @NotNull(message = "device_id is required")
    private UUID deviceId;

    @NotNull(message = "events are required")
    @Size(min = 1, max = 100, message = "events must contain 1 to 100 items")
    @Valid
    private List<AuditEventItem> events;
}
