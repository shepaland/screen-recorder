package com.prg.auth.controller;

import com.prg.auth.dto.request.CreateDeviceTokenRequest;
import com.prg.auth.dto.response.DeviceTokenResponse;
import com.prg.auth.dto.response.PageResponse;
import com.prg.auth.exception.AccessDeniedException;
import com.prg.auth.security.UserPrincipal;
import com.prg.auth.service.DeviceTokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/device-tokens")
@RequiredArgsConstructor
@Validated
public class DeviceTokenController {

    private final DeviceTokenService deviceTokenService;

    @PostMapping
    public ResponseEntity<DeviceTokenResponse> createToken(
            @Valid @RequestBody CreateDeviceTokenRequest request,
            @AuthenticationPrincipal UserPrincipal principal,
            HttpServletRequest httpRequest) {
        requirePermission(principal, "DEVICE_TOKENS:CREATE");
        DeviceTokenResponse response = deviceTokenService.createToken(
                request, principal,
                getClientIp(httpRequest), httpRequest.getHeader("User-Agent"));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<PageResponse<DeviceTokenResponse>> getTokens(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String search,
            @RequestParam(name = "is_active", required = false) Boolean isActive) {
        requirePermission(principal, "DEVICE_TOKENS:READ");
        PageResponse<DeviceTokenResponse> response = deviceTokenService.getTokens(
                principal, page, size, search, isActive);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<DeviceTokenResponse> getToken(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        requirePermission(principal, "DEVICE_TOKENS:READ");
        DeviceTokenResponse response = deviceTokenService.getToken(id, principal);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deactivateToken(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal,
            HttpServletRequest httpRequest) {
        requirePermission(principal, "DEVICE_TOKENS:DELETE");
        deviceTokenService.deactivateToken(id, principal,
                getClientIp(httpRequest), httpRequest.getHeader("User-Agent"));
        return ResponseEntity.noContent().build();
    }

    private void requirePermission(UserPrincipal principal, String permission) {
        if (!principal.hasPermission(permission)) {
            throw new AccessDeniedException("You do not have permission: " + permission);
        }
    }

    private String getClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isEmpty()) return xff.split(",")[0].trim();
        return request.getRemoteAddr();
    }
}
