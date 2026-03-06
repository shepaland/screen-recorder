package com.prg.auth.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ValidateRegistrationTokenResponse {

    private boolean valid;
    private String tenantName;
    private String tokenName;
    private String reason;
}
