package com.prg.ingest.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditEventItem {

    @NotNull(message = "event id is required")
    private UUID id;

    @NotNull(message = "event_type is required")
    @Pattern(regexp = "SESSION_LOCK|SESSION_UNLOCK|SESSION_LOGON|SESSION_LOGOFF|PROCESS_START|PROCESS_STOP|USER_LOGON|USER_LOGOFF",
             message = "event_type must be one of: SESSION_LOCK, SESSION_UNLOCK, SESSION_LOGON, SESSION_LOGOFF, PROCESS_START, PROCESS_STOP, USER_LOGON, USER_LOGOFF")
    private String eventType;

    @NotNull(message = "event_ts is required")
    private Instant eventTs;

    private UUID sessionId;

    @NotNull(message = "details are required")
    private Map<String, Object> details;
}
