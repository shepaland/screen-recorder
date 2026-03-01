package com.prg.auth.controller;

import com.prg.auth.dto.request.DeviceLoginRequest;
import com.prg.auth.dto.request.DeviceRefreshRequest;
import com.prg.auth.dto.response.DeviceLoginResponse;
import com.prg.auth.dto.response.DeviceRefreshResponse;
import com.prg.auth.service.DeviceAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Slf4j
public class DeviceAuthController {

    private final DeviceAuthService deviceAuthService;

    @PostMapping("/device-login")
    public ResponseEntity<DeviceLoginResponse> deviceLogin(
            @Valid @RequestBody DeviceLoginRequest request,
            HttpServletRequest httpRequest) {
        String ipAddress = getClientIp(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");

        DeviceLoginResponse response = deviceAuthService.deviceLogin(request, ipAddress, userAgent);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/device-refresh")
    public ResponseEntity<DeviceRefreshResponse> deviceRefresh(
            @Valid @RequestBody DeviceRefreshRequest request,
            HttpServletRequest httpRequest) {
        String ipAddress = getClientIp(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");

        DeviceRefreshResponse response = deviceAuthService.deviceRefresh(request, ipAddress, userAgent);
        return ResponseEntity.ok(response);
    }

    private String getClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isEmpty()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
