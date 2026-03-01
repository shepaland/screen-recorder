package com.prg.controlplane.controller;

import com.prg.controlplane.dto.request.AckCommandRequest;
import com.prg.controlplane.dto.request.CreateCommandRequest;
import com.prg.controlplane.dto.response.CommandResponse;
import com.prg.controlplane.dto.response.PageResponse;
import com.prg.controlplane.exception.AccessDeniedException;
import com.prg.controlplane.security.DevicePrincipal;
import com.prg.controlplane.service.DeviceCommandService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/devices")
@RequiredArgsConstructor
public class DeviceCommandController {

    private final DeviceCommandService deviceCommandService;

    @PostMapping("/{deviceId}/commands")
    public ResponseEntity<CommandResponse> createCommand(
            @PathVariable UUID deviceId,
            @Valid @RequestBody CreateCommandRequest request,
            HttpServletRequest httpRequest) {
        DevicePrincipal principal = getPrincipal(httpRequest);
        requirePermission(principal, "DEVICES:COMMAND");

        CommandResponse response = deviceCommandService.createCommand(
                deviceId, request, principal.getTenantId(), principal.getUserId());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @PutMapping("/commands/{commandId}/ack")
    public ResponseEntity<Void> acknowledgeCommand(
            @PathVariable UUID commandId,
            @Valid @RequestBody AckCommandRequest request,
            HttpServletRequest httpRequest) {
        DevicePrincipal principal = getPrincipal(httpRequest);

        deviceCommandService.acknowledgeCommand(commandId, request, principal);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{deviceId}/commands")
    public ResponseEntity<PageResponse<CommandResponse>> getCommands(
            @PathVariable UUID deviceId,
            HttpServletRequest httpRequest,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status) {
        DevicePrincipal principal = getPrincipal(httpRequest);
        requirePermission(principal, "DEVICES:READ");

        PageResponse<CommandResponse> response = deviceCommandService.getCommands(
                deviceId, principal.getTenantId(), status, page, size);
        return ResponseEntity.ok(response);
    }

    private DevicePrincipal getPrincipal(HttpServletRequest request) {
        DevicePrincipal principal = (DevicePrincipal) request.getAttribute("principal");
        if (principal == null) {
            throw new AccessDeniedException("Authentication required");
        }
        return principal;
    }

    private void requirePermission(DevicePrincipal principal, String permission) {
        if (!principal.hasPermission(permission)) {
            throw new AccessDeniedException("You do not have permission: " + permission);
        }
    }
}
