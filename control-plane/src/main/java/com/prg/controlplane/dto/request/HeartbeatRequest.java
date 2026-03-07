package com.prg.controlplane.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HeartbeatRequest {

    @NotBlank(message = "Status is required")
    @Pattern(regexp = "^(offline|online|recording|error|idle)$", message = "Invalid status value")
    private String status;

    private String agentVersion;

    private String timezone;

    private String osType;

    private Boolean sessionLocked;

    private Map<String, Object> metrics;
}
