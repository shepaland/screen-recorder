package com.prg.ingest.dto.employee;

import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroupMetricsResponse {
    private long totalEmployees;
    private long activeEmployees;
}
