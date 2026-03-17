package com.prg.playback.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prg.playback.security.DevicePrincipal;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@Order(1)
@Slf4j
public class JwtValidationFilter implements Filter {

    public static final String DEVICE_PRINCIPAL_ATTRIBUTE = "devicePrincipal";

    @Value("${prg.auth-service.base-url}")
    private String authServiceUrl;

    @Value("${prg.auth-service.internal-api-key:}")
    private String internalApiKey;

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String path = httpRequest.getRequestURI();
        if (path.startsWith("/actuator") || path.startsWith("/swagger") || path.startsWith("/v3/api-docs")) {
            chain.doFilter(request, response);
            return;
        }

        String authHeader = httpRequest.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            httpResponse.setStatus(401);
            httpResponse.setContentType("application/json");
            httpResponse.getWriter().write("{\"error\":\"Missing or invalid Authorization header\"}");
            return;
        }

        String token = authHeader.substring(7);
        try {
            String jsonBody = objectMapper.writeValueAsString(Map.of("token", token));

            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(authServiceUrl + "/api/v1/internal/validate-token"))
                .header("Content-Type", "application/json")
                .header("X-Internal-API-Key", internalApiKey)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofSeconds(10))
                .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() != 200) {
                log.debug("Token validation failed: status={}", resp.statusCode());
                httpResponse.setStatus(401);
                httpResponse.setContentType("application/json");
                httpResponse.getWriter().write("{\"error\":\"Invalid token\"}");
                return;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> result = objectMapper.readValue(resp.body(), Map.class);
            Boolean valid = (Boolean) result.get("valid");
            if (valid == null || !valid) {
                httpResponse.setStatus(401);
                httpResponse.setContentType("application/json");
                httpResponse.getWriter().write("{\"error\":\"Invalid token\"}");
                return;
            }

            DevicePrincipal principal = DevicePrincipal.builder()
                .userId(toUUID(result.get("user_id")))
                .tenantId(toUUID(result.get("tenant_id")))
                .roles(toStringList(result.get("roles")))
                .permissions(toStringList(result.get("permissions")))
                .scopes(toStringList(result.get("scopes")))
                .build();

            httpRequest.setAttribute(DEVICE_PRINCIPAL_ATTRIBUTE, principal);
            chain.doFilter(request, response);
        } catch (IOException | ServletException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("Token validation interrupted: {}", e.getMessage());
            httpResponse.setStatus(401);
            httpResponse.setContentType("application/json");
            httpResponse.getWriter().write("{\"error\":\"Token validation failed\"}");
        } catch (Exception e) {
            // Only catch auth-related errors (e.g. connection to auth-service failed)
            // If the error comes from downstream (S3, etc.), it should propagate as 500
            if (httpRequest.getAttribute(DEVICE_PRINCIPAL_ATTRIBUTE) != null) {
                // Principal was already set — error is from downstream, not auth
                throw new ServletException(e);
            }
            log.debug("Token validation failed: {}", e.getMessage());
            httpResponse.setStatus(401);
            httpResponse.setContentType("application/json");
            httpResponse.getWriter().write("{\"error\":\"Token validation failed\"}");
        }
    }

    private UUID toUUID(Object v) {
        if (v == null) return null;
        return UUID.fromString(v.toString());
    }

    @SuppressWarnings("unchecked")
    private List<String> toStringList(Object v) {
        if (v instanceof List) return (List<String>) v;
        return List.of();
    }
}
