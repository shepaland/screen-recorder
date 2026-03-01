package com.prg.ingest.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prg.ingest.security.DevicePrincipal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@Slf4j
public class AuthServiceClient {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String authServiceBaseUrl;
    private final String internalApiKey;

    public AuthServiceClient(
            ObjectMapper objectMapper,
            @Value("${prg.auth-service.base-url}") String authServiceBaseUrl,
            @Value("${prg.auth-service.internal-api-key}") String internalApiKey) {
        this.objectMapper = objectMapper;
        this.authServiceBaseUrl = authServiceBaseUrl;
        this.internalApiKey = internalApiKey;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @SuppressWarnings("unchecked")
    public DevicePrincipal validateToken(String token, UUID deviceId) {
        try {
            Map<String, String> requestBody = Map.of("token", token);
            String jsonBody = objectMapper.writeValueAsString(requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(authServiceBaseUrl + "/api/v1/internal/validate-token"))
                    .header("Content-Type", "application/json")
                    .header("X-Internal-API-Key", internalApiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("Auth service returned status {}", response.statusCode());
                return null;
            }

            Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);
            Boolean valid = (Boolean) result.get("valid");

            if (valid == null || !valid) {
                log.debug("Token validation failed: {}", result.get("reason"));
                return null;
            }

            UUID userId = UUID.fromString((String) result.get("user_id"));
            UUID tenantId = UUID.fromString((String) result.get("tenant_id"));
            List<String> roles = (List<String>) result.getOrDefault("roles", List.of());
            List<String> permissions = (List<String>) result.getOrDefault("permissions", List.of());
            List<String> scopes = (List<String>) result.getOrDefault("scopes", List.of());

            return DevicePrincipal.builder()
                    .userId(userId)
                    .tenantId(tenantId)
                    .deviceId(deviceId)
                    .roles(roles)
                    .permissions(permissions)
                    .scopes(scopes)
                    .build();

        } catch (Exception e) {
            log.error("Failed to validate token with auth-service: {}", e.getMessage(), e);
            return null;
        }
    }
}
