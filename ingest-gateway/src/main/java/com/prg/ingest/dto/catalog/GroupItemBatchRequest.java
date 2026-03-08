package com.prg.ingest.dto.catalog;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroupItemBatchRequest {

    @NotEmpty(message = "items list must not be empty")
    @Valid
    private List<GroupItemCreateRequest> items;
}
