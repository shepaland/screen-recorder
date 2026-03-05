package com.prg.auth.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InitiateOtpResponse {
    private String message;
    private UUID codeId;
    private int expiresIn;
    private int resendAvailableIn;
}
