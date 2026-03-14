package com.prg.ingest.dto.employee;

import lombok.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmployeeGroupResponse {
    private UUID id;
    private UUID parentId;
    private String name;
    private String description;
    private String color;
    private int sortOrder;
    private long memberCount;
    private long totalMemberCount;
    @Builder.Default
    private List<EmployeeGroupResponse> children = new ArrayList<>();
    private Instant createdAt;
    private Instant updatedAt;
}
