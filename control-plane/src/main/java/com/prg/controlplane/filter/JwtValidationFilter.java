package com.prg.controlplane.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prg.controlplane.client.AuthServiceClient;
import com.prg.controlplane.dto.response.ErrorResponse;
import com.prg.controlplane.security.DevicePrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtValidationFilter extends OncePerRequestFilter {

    private final AuthServiceClient authServiceClient;
    private final ObjectMapper objectMapper;

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String PRINCIPAL_ATTRIBUTE = "principal";
    private static final String DEVICE_ID_HEADER = "X-Device-ID";

    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/actuator/health",
            "/actuator/info"
    );

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        String authHeader = request.getHeader(AUTHORIZATION_HEADER);

        if (!StringUtils.hasText(authHeader) || !authHeader.startsWith(BEARER_PREFIX)) {
            sendUnauthorized(response, "Missing or invalid Authorization header");
            return;
        }

        String token = authHeader.substring(BEARER_PREFIX.length());

        AuthServiceClient.ValidateTokenResponse validation = authServiceClient.validateToken(token);

        if (!validation.isValid()) {
            log.debug("JWT validation failed: {}", validation.getReason());
            sendUnauthorized(response, "Invalid or expired token");
            return;
        }

        // Extract device_id from X-Device-ID header or from token claims
        UUID deviceId = validation.getDeviceId();
        String deviceIdHeader = request.getHeader(DEVICE_ID_HEADER);
        if (deviceId == null && StringUtils.hasText(deviceIdHeader)) {
            try {
                deviceId = UUID.fromString(deviceIdHeader);
            } catch (IllegalArgumentException e) {
                log.warn("Invalid X-Device-ID header value: {}", deviceIdHeader);
            }
        }

        DevicePrincipal principal = DevicePrincipal.builder()
                .userId(validation.getUserId())
                .tenantId(validation.getTenantId())
                .deviceId(deviceId)
                .roles(validation.getRoles() != null ? validation.getRoles() : java.util.List.of())
                .permissions(validation.getPermissions() != null ? validation.getPermissions() : java.util.List.of())
                .scopes(validation.getScopes() != null ? validation.getScopes() : java.util.List.of())
                .build();

        request.setAttribute(PRINCIPAL_ATTRIBUTE, principal);

        // Set MDC for structured logging
        if (principal.getTenantId() != null) {
            MDC.put("tenant_id", principal.getTenantId().toString());
        }
        if (principal.getUserId() != null) {
            MDC.put("user_id", principal.getUserId().toString());
        }
        if (principal.getDeviceId() != null) {
            MDC.put("device_id", principal.getDeviceId().toString());
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();

        // Skip public endpoints
        for (String publicPath : PUBLIC_PATHS) {
            if (path.startsWith(publicPath)) {
                return true;
            }
        }

        // Skip internal API endpoints (handled by InternalApiKeyFilter)
        if (path.startsWith("/api/v1/internal/")) {
            return true;
        }

        return false;
    }

    private void sendUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(),
                ErrorResponse.builder().error(message).code("UNAUTHORIZED").build());
    }
}
