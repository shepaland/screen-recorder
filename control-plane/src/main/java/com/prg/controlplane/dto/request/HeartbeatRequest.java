package com.prg.controlplane.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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
    @Pattern(regexp = "^(offline|online|recording|error|idle|starting|configuring|awaiting_user|stopped|recording_disabled|desktop_unavailable)$",
            message = "Invalid status value")
    private String status;

    @Size(max = 50, message = "Agent version must not exceed 50 characters")
    private String agentVersion;

    @Size(max = 100, message = "Timezone must not exceed 100 characters")
    private String timezone;

    @Size(max = 50, message = "OS type must not exceed 50 characters")
    private String osType;

    private Boolean sessionLocked;

    private Map<String, Object> metrics;
}
