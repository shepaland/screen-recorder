package com.prg.ingest.controller;

import com.prg.ingest.dto.request.CreateSessionRequest;
import com.prg.ingest.dto.response.SessionResponse;
import com.prg.ingest.filter.JwtValidationFilter;
import com.prg.ingest.security.DevicePrincipal;
import com.prg.ingest.service.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ingest/sessions")
@RequiredArgsConstructor
@Slf4j
public class SessionController {

    private final SessionService sessionService;

    @PostMapping
    public ResponseEntity<SessionResponse> createSession(
            @Valid @RequestBody CreateSessionRequest request,
            HttpServletRequest httpRequest) {
        DevicePrincipal principal = getPrincipal(httpRequest);
        SessionResponse response = sessionService.createSession(request, principal);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}/end")
    public ResponseEntity<SessionResponse> endSession(
            @PathVariable UUID id,
            HttpServletRequest httpRequest) {
        DevicePrincipal principal = getPrincipal(httpRequest);
        SessionResponse response = sessionService.endSession(id, principal);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<SessionResponse> getSession(
            @PathVariable UUID id,
            HttpServletRequest httpRequest) {
        DevicePrincipal principal = getPrincipal(httpRequest);
        SessionResponse response = sessionService.getSession(id, principal);
        return ResponseEntity.ok(response);
    }

    private DevicePrincipal getPrincipal(HttpServletRequest request) {
        DevicePrincipal principal = (DevicePrincipal) request.getAttribute(
                JwtValidationFilter.DEVICE_PRINCIPAL_ATTRIBUTE);
        if (principal == null) {
            throw new IllegalStateException("DevicePrincipal not found in request attributes");
        }
        return principal;
    }
}
