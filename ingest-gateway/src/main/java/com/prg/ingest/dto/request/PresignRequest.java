package com.prg.ingest.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PresignRequest {

    @NotNull(message = "device_id is required")
    private UUID deviceId;

    @NotNull(message = "session_id is required")
    private UUID sessionId;

    @NotNull(message = "sequence_num is required")
    @Min(value = 0, message = "sequence_num must be >= 0")
    private Integer sequenceNum;

    @NotNull(message = "size_bytes is required")
    @Min(value = 1, message = "size_bytes must be > 0")
    private Long sizeBytes;

    @NotNull(message = "duration_ms is required")
    @Min(value = 1, message = "duration_ms must be > 0")
    private Integer durationMs;

    @NotBlank(message = "checksum_sha256 is required")
    private String checksumSha256;

    private String contentType;

    private Map<String, Object> metadata;
}
