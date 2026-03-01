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
public class AckCommandRequest {

    @NotBlank(message = "Status is required")
    @Pattern(regexp = "^(acknowledged|failed)$", message = "Status must be 'acknowledged' or 'failed'")
    private String status;

    private Map<String, Object> result;
}
