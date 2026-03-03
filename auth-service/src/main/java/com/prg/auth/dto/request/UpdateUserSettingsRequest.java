package com.prg.auth.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateUserSettingsRequest {

    @Min(value = 1, message = "Session TTL must be at least 1 day")
    @Max(value = 365, message = "Session TTL must not exceed 365 days")
    private Integer sessionTtlDays;
}
