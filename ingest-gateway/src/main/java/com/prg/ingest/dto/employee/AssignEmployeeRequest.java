package com.prg.ingest.dto.employee;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AssignEmployeeRequest {

    @NotBlank
    @Size(max = 512)
    private String username;
}
