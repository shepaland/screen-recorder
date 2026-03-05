package com.prg.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerifyOtpRequest {

    @NotNull(message = "code_id is required")
    private UUID codeId;

    @NotBlank(message = "Code is required")
    @Pattern(regexp = "^\\d{6}$", message = "Code must be exactly 6 digits")
    private String code;
}
