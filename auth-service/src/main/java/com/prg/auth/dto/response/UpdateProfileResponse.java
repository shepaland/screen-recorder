package com.prg.auth.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateProfileResponse {
    private UUID id;
    private String firstName;
    private String lastName;
    private Instant updatedTs;
}
