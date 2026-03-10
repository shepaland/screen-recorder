package com.prg.auth.dto.request;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateDeviceTokenRequest {

    @Size(min = 1, max = 255, message = "Token name must be between 1 and 255 characters")
    private String name;

    @Positive(message = "Max uses must be a positive number")
    private Integer maxUses;

    private Boolean recordingEnabled;
}
