package com.prg.ingest.controller;

import com.prg.ingest.dto.response.DeviceStorageStatsResponse;
import com.prg.ingest.repository.SegmentRepository;
import com.prg.ingest.security.DevicePrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/ingest/devices")
@RequiredArgsConstructor
@Slf4j
public class StorageStatsController {

    private final SegmentRepository segmentRepository;

    @Value("${prg.auth-service.internal-api-key:}")
    private String internalApiKey;

    @GetMapping("/storage-stats")
    public ResponseEntity<List<DeviceStorageStatsResponse>> getStorageStats(
            HttpServletRequest httpRequest,
            @RequestParam(name = "tenant_id") UUID tenantId,
            @RequestParam(name = "device_ids") String deviceIdsStr) {

        // Auth: check either internal API key or JWT with DEVICES:READ
        String apiKey = httpRequest.getHeader("X-Internal-API-Key");
        boolean isInternalCall = apiKey != null && !apiKey.isEmpty() && apiKey.equals(internalApiKey);

        if (!isInternalCall) {
            DevicePrincipal principal = (DevicePrincipal) httpRequest.getAttribute("devicePrincipal");
            if (principal == null || !principal.getPermissions().contains("DEVICES:READ")) {
                return ResponseEntity.status(403).build();
            }
            // Use tenant from JWT for non-internal calls
            tenantId = principal.getTenantId();
        }

        // Parse device IDs
        String[] idParts = deviceIdsStr.split(",");
        if (idParts.length > 500) {
            return ResponseEntity.badRequest().build();
        }

        List<UUID> deviceIds;
        try {
            deviceIds = Arrays.stream(idParts)
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(UUID::fromString)
                    .collect(Collectors.toList());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }

        if (deviceIds.isEmpty()) {
            return ResponseEntity.ok(List.of());
        }

        List<Object[]> results = segmentRepository.getStorageStatsByDeviceIds(tenantId, deviceIds);

        List<DeviceStorageStatsResponse> stats = results.stream()
                .map(row -> DeviceStorageStatsResponse.builder()
                        .deviceId((UUID) row[0])
                        .totalBytes(((Number) row[1]).longValue())
                        .segmentCount(((Number) row[2]).longValue())
                        .build())
                .toList();

        return ResponseEntity.ok(stats);
    }
}
