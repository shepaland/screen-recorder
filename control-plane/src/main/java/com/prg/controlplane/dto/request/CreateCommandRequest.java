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
public class CreateCommandRequest {

    @NotBlank(message = "Command type is required")
    @Pattern(regexp = "^(START_RECORDING|STOP_RECORDING|UPDATE_SETTINGS|RESTART_AGENT|UNREGISTER|UPLOAD_LOGS)$",
             message = "Invalid command type")
    private String commandType;

    private Map<String, Object> payload;
}
