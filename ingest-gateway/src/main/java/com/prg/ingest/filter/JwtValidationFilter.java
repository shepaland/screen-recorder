package com.prg.ingest.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prg.ingest.client.AuthServiceClient;
import com.prg.ingest.dto.response.ErrorResponse;
import com.prg.ingest.security.DevicePrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtValidationFilter extends OncePerRequestFilter {

    public static final String DEVICE_PRINCIPAL_ATTRIBUTE = "devicePrincipal";

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String DEVICE_ID_HEADER = "X-Device-ID";

    private final AuthServiceClient authServiceClient;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {

        String jwt = extractJwtFromRequest(request);
        if (!StringUtils.hasText(jwt)) {
            sendUnauthorized(response, "Missing or invalid Authorization header", "MISSING_TOKEN");
            return;
        }

        String deviceIdHeader = request.getHeader(DEVICE_ID_HEADER);
        UUID deviceId = null;
        if (StringUtils.hasText(deviceIdHeader)) {
            try {
                deviceId = UUID.fromString(deviceIdHeader);
            } catch (IllegalArgumentException e) {
                sendBadRequest(response, "Invalid X-Device-ID header format", "INVALID_DEVICE_ID");
                return;
            }
        }

        DevicePrincipal principal = authServiceClient.validateToken(jwt, deviceId);
        if (principal == null) {
            sendUnauthorized(response, "Invalid or expired token", "INVALID_TOKEN");
            return;
        }

        request.setAttribute(DEVICE_PRINCIPAL_ATTRIBUTE, principal);

        MDC.put("user_id", principal.getUserId().toString());
        MDC.put("tenant_id", principal.getTenantId().toString());
        if (principal.getDeviceId() != null) {
            MDC.put("device_id", principal.getDeviceId().toString());
        }

        filterChain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator/");
    }

    private String extractJwtFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader(AUTHORIZATION_HEADER);
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith(BEARER_PREFIX)) {
            return bearerToken.substring(BEARER_PREFIX.length());
        }
        return null;
    }

    private void sendUnauthorized(HttpServletResponse response, String message, String code)
            throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ErrorResponse error = ErrorResponse.builder().error(message).code(code).build();
        response.getWriter().write(objectMapper.writeValueAsString(error));
    }

    private void sendBadRequest(HttpServletResponse response, String message, String code)
            throws IOException {
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ErrorResponse error = ErrorResponse.builder().error(message).code(code).build();
        response.getWriter().write(objectMapper.writeValueAsString(error));
    }
}
