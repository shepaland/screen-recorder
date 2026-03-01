package com.prg.auth.controller;

import com.prg.auth.dto.response.AuditLogResponse;
import com.prg.auth.dto.response.PageResponse;
import com.prg.auth.exception.AccessDeniedException;
import com.prg.auth.security.UserPrincipal;
import com.prg.auth.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/audit-logs")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditService auditService;

    @GetMapping
    public ResponseEntity<PageResponse<AuditLogResponse>> getAuditLogs(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant fromTs,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant toTs) {
        if (!principal.hasPermission("AUDIT:READ")) {
            throw new AccessDeniedException("You do not have permission: AUDIT:READ");
        }

        PageResponse<AuditLogResponse> response = auditService.getAuditLogs(
                principal.getTenantId(), userId, action, resourceType, fromTs, toTs, page, size);
        return ResponseEntity.ok(response);
    }
}
