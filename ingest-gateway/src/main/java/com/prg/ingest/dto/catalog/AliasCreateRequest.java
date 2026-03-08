package com.prg.ingest.dto.catalog;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AliasCreateRequest {

    @NotNull(message = "alias_type is required")
    private String aliasType;

    @NotBlank(message = "original is required")
    @Size(max = 512, message = "original must not exceed 512 characters")
    private String original;

    @NotBlank(message = "display_name is required")
    @Size(max = 200, message = "display_name must not exceed 200 characters")
    private String displayName;

    @Size(max = 1024, message = "icon_url must not exceed 1024 characters")
    private String iconUrl;
}
