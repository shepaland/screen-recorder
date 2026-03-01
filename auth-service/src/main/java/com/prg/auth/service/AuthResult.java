package com.prg.auth.service;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Internal result holder for auth operations that need to return
 * both the API response and the raw refresh token for cookie setting.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthResult<T> {
    private T response;
    private String rawRefreshToken;
}
