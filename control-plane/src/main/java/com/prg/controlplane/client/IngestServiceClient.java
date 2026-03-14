package com.prg.controlplane.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@Slf4j
public class IngestServiceClient {

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String internalApiKey;

    public IngestServiceClient(
            @Value("${prg.ingest-service.base-url:http://localhost:8084}") String baseUrl,
            @Value("${prg.auth-service.internal-api-key}") String internalApiKey,
            ObjectMapper objectMapper) {
        this.restTemplate = new RestTemplate();
        var converter = new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter();
        converter.setObjectMapper(objectMapper);
        this.restTemplate.setMessageConverters(List.of(converter));
        this.baseUrl = baseUrl;
        this.internalApiKey = internalApiKey;
    }

    /**
     * Get storage stats (total bytes, segment count) for a list of devices.
     */
    public List<DeviceStorageStats> getStorageStats(UUID tenantId, List<UUID> deviceIds) {
        if (deviceIds == null || deviceIds.isEmpty()) {
            return List.of();
        }

        String deviceIdsParam = deviceIds.stream()
                .map(UUID::toString)
                .collect(Collectors.joining(","));

        String url = baseUrl + "/api/v1/ingest/devices/storage-stats?tenant_id=" + tenantId
                + "&device_ids=" + deviceIdsParam;

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-API-Key", internalApiKey);

        try {
            ResponseEntity<List<DeviceStorageStats>> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers),
                    new ParameterizedTypeReference<>() {});

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody();
            }

            log.warn("Storage stats returned non-success status: {}", response.getStatusCode());
            return List.of();
        } catch (RestClientException e) {
            log.error("Failed to get storage stats from ingest-gateway: {}", e.getMessage());
            return List.of();
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DeviceStorageStats {
        private UUID deviceId;
        private Long totalBytes;
        private Long segmentCount;
    }
}
