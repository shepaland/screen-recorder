package com.prg.ingest.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
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
public class SubmitFocusIntervalsRequest {

    @NotNull(message = "device_id is required")
    private UUID deviceId;

    @NotBlank(message = "username is required")
    @Size(max = 256, message = "username must not exceed 256 characters")
    private String username;

    @NotEmpty(message = "intervals must not be empty")
    @Size(max = 100, message = "intervals must contain at most 100 items")
    @Valid
    private List<FocusIntervalItem> intervals;
}
