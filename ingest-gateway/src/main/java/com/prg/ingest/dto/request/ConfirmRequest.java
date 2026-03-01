package com.prg.ingest.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConfirmRequest {

    @NotNull(message = "segment_id is required")
    private UUID segmentId;

    @NotBlank(message = "checksum_sha256 is required")
    private String checksumSha256;
}
