package com.prg.ingest.controller;

import com.prg.ingest.dto.request.ConfirmRequest;
import com.prg.ingest.dto.request.PresignRequest;
import com.prg.ingest.dto.response.ConfirmResponse;
import com.prg.ingest.dto.response.PresignResponse;
import com.prg.ingest.filter.JwtValidationFilter;
import com.prg.ingest.security.DevicePrincipal;
import com.prg.ingest.service.IngestService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/ingest")
@RequiredArgsConstructor
@Slf4j
public class IngestController {

    private final IngestService ingestService;

    @PostMapping("/presign")
    public ResponseEntity<PresignResponse> presign(
            @Valid @RequestBody PresignRequest request,
            HttpServletRequest httpRequest) {
        DevicePrincipal principal = getPrincipal(httpRequest);
        PresignResponse response = ingestService.presign(request, principal);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/confirm")
    public ResponseEntity<ConfirmResponse> confirm(
            @Valid @RequestBody ConfirmRequest request,
            HttpServletRequest httpRequest) {
        DevicePrincipal principal = getPrincipal(httpRequest);
        ConfirmResponse response = ingestService.confirm(request, principal);
        HttpStatus status = "accepted".equals(response.getStatus())
            ? HttpStatus.ACCEPTED
            : HttpStatus.OK;
        return ResponseEntity.status(status).body(response);
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
