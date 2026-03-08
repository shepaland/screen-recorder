package com.prg.ingest.dto.catalog;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AliasUpdateRequest {

    @Size(max = 200, message = "display_name must not exceed 200 characters")
    private String displayName;

    @Size(max = 1024, message = "icon_url must not exceed 1024 characters")
    private String iconUrl;
}
