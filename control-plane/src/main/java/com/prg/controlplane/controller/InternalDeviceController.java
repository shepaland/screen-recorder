package com.prg.controlplane.controller;

import com.prg.controlplane.dto.response.DeviceResponse;
import com.prg.controlplane.service.DeviceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/internal/devices")
@RequiredArgsConstructor
public class InternalDeviceController {

    private final DeviceService deviceService;

    @PutMapping("/{id}/restore")
    public ResponseEntity<Map<String, Boolean>> restoreDevice(
            @PathVariable UUID id,
            @RequestHeader("X-Tenant-ID") UUID tenantId) {
        DeviceResponse response = deviceService.restoreDevice(id, tenantId);
        boolean restored = !Boolean.TRUE.equals(response.getIsDeleted());
        return ResponseEntity.ok(Map.of("restored", restored));
    }
}
