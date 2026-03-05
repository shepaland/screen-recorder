package com.prg.auth.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerifyOtpResponse {
    private String accessToken;
    private String tokenType;
    private long expiresIn;
    private UserResponse user;
    private boolean isNewUser;
}
