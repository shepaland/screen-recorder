package com.prg.controlplane.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HeartbeatResponse {
    private Instant serverTs;
    private List<CommandResponse> pendingCommands;
    private int nextHeartbeatSec;
    private Map<String, Object> deviceSettings;
}
