package com.prg.ingest.dto.catalog;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroupItemCreateRequest {

    @NotBlank(message = "pattern is required")
    @Size(max = 512, message = "pattern must not exceed 512 characters")
    private String pattern;

    private String matchType;
}
