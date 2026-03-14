package com.prg.ingest.dto.employee;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmployeeGroupUpdateRequest {

    @Size(max = 200)
    private String name;

    @Size(max = 1000)
    private String description;

    @Pattern(regexp = "^#[0-9a-fA-F]{6}$", message = "Color must be a hex value like #FF0000")
    private String color;

    private Integer sortOrder;

    private UUID parentId;
}
