package com.prg.ingest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FocusIntervalsResponse {
    private int accepted;
    private int duplicates;
    private int total;
    private UUID correlationId;
}
