package com.prg.controlplane.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@Slf4j
public class AuthServiceClient {

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String internalApiKey;

    public AuthServiceClient(
            @Value("${prg.auth-service.base-url}") String baseUrl,
            @Value("${prg.auth-service.internal-api-key}") String internalApiKey,
            ObjectMapper objectMapper) {
        this.restTemplate = new RestTemplate();
        // Configure RestTemplate to use Spring's ObjectMapper (snake_case)
        org.springframework.http.converter.json.MappingJackson2HttpMessageConverter converter =
                new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter();
        converter.setObjectMapper(objectMapper);
        this.restTemplate.setMessageConverters(java.util.List.of(converter));
        this.baseUrl = baseUrl;
        this.internalApiKey = internalApiKey;
    }

    public ValidateTokenResponse validateToken(String token) {
        String url = baseUrl + "/api/v1/internal/validate-token";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-API-Key", internalApiKey);

        Map<String, String> body = Map.of("token", token);

        try {
            HttpEntity<Map<String, String>> request = new HttpEntity<>(body, headers);
            ResponseEntity<ValidateTokenResponse> response = restTemplate.exchange(
                    url, HttpMethod.POST, request, ValidateTokenResponse.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody();
            }

            log.warn("Token validation returned non-success status: {}", response.getStatusCode());
            return ValidateTokenResponse.builder().valid(false).reason("Validation failed").build();
        } catch (RestClientException e) {
            log.error("Failed to validate token via auth-service: {}", e.getMessage());
            return ValidateTokenResponse.builder().valid(false).reason("Auth service unavailable").build();
        }
    }

    public CheckAccessResponse checkAccess(UUID userId, UUID tenantId, String permission,
                                            String resourceType, UUID resourceId, UUID resourceOwnerId) {
        String url = baseUrl + "/api/v1/internal/check-access";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-API-Key", internalApiKey);

        CheckAccessRequest body = CheckAccessRequest.builder()
                .userId(userId)
                .tenantId(tenantId)
                .permission(permission)
                .resourceType(resourceType)
                .resourceId(resourceId)
                .resourceOwnerId(resourceOwnerId)
                .build();

        try {
            HttpEntity<CheckAccessRequest> request = new HttpEntity<>(body, headers);
            ResponseEntity<CheckAccessResponse> response = restTemplate.exchange(
                    url, HttpMethod.POST, request, CheckAccessResponse.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody();
            }

            log.warn("Access check returned non-success status: {}", response.getStatusCode());
            return CheckAccessResponse.builder().allowed(false).reason("Access check failed").build();
        } catch (RestClientException e) {
            log.error("Failed to check access via auth-service: {}", e.getMessage());
            return CheckAccessResponse.builder().allowed(false).reason("Auth service unavailable").build();
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ValidateTokenResponse {
        private boolean valid;
        private UUID userId;
        private UUID tenantId;
        private UUID deviceId;
        private List<String> roles;
        private List<String> permissions;
        private List<String> scopes;
        private String reason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CheckAccessRequest {
        private UUID userId;
        private UUID tenantId;
        private String permission;
        private String resourceType;
        private UUID resourceId;
        private UUID resourceOwnerId;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CheckAccessResponse {
        private boolean allowed;
        private String reason;
    }
}
