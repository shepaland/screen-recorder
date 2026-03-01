package com.prg.auth.controller;

import com.prg.auth.dto.request.CheckAccessRequest;
import com.prg.auth.dto.request.ValidateTokenRequest;
import com.prg.auth.dto.response.CheckAccessResponse;
import com.prg.auth.dto.response.ValidateTokenResponse;
import com.prg.auth.security.JwtTokenProvider;
import com.prg.auth.security.UserPrincipal;
import com.prg.auth.service.AccessControlService;
import io.jsonwebtoken.Claims;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/internal")
@RequiredArgsConstructor
@Slf4j
public class InternalController {

    private final AccessControlService accessControlService;
    private final JwtTokenProvider jwtTokenProvider;

    @PostMapping("/check-access")
    public ResponseEntity<CheckAccessResponse> checkAccess(@Valid @RequestBody CheckAccessRequest request) {
        CheckAccessResponse response = accessControlService.checkAccess(request);
        return ResponseEntity.ok(response);
    }

    @SuppressWarnings("unchecked")
    @PostMapping("/validate-token")
    public ResponseEntity<ValidateTokenResponse> validateToken(@Valid @RequestBody ValidateTokenRequest request) {
        try {
            if (!jwtTokenProvider.validateToken(request.getToken())) {
                return ResponseEntity.ok(ValidateTokenResponse.builder()
                        .valid(false)
                        .reason("Invalid token")
                        .build());
            }

            UserPrincipal principal = jwtTokenProvider.getUserPrincipalFromToken(request.getToken());

            return ResponseEntity.ok(ValidateTokenResponse.builder()
                    .valid(true)
                    .userId(principal.getUserId())
                    .tenantId(principal.getTenantId())
                    .roles(principal.getRoles())
                    .permissions(principal.getPermissions())
                    .scopes(principal.getScopes())
                    .build());
        } catch (Exception e) {
            log.debug("Token validation failed: {}", e.getMessage());
            return ResponseEntity.ok(ValidateTokenResponse.builder()
                    .valid(false)
                    .reason(e.getMessage())
                    .build());
        }
    }
}
