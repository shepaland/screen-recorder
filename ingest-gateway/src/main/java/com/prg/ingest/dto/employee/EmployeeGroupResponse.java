package com.prg.ingest.dto.employee;

import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmployeeGroupResponse {
    private UUID id;
    private String name;
    private String description;
    private String color;
    private int sortOrder;
    private long memberCount;
    private Instant createdAt;
    private Instant updatedAt;
}
