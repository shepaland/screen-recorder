package com.prg.ingest.dto.employee;

import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmployeeGroupMemberResponse {
    private UUID id;
    private UUID groupId;
    private String username;
    private Instant createdAt;
}
