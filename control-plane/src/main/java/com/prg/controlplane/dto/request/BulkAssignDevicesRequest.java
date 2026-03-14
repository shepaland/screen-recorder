package com.prg.controlplane.dto.request;

import jakarta.validation.constraints.NotEmpty;
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
public class BulkAssignDevicesRequest {

    @NotEmpty(message = "device_ids must not be empty")
    @Size(max = 100, message = "Cannot assign more than 100 devices at once")
    private List<UUID> deviceIds;
}
