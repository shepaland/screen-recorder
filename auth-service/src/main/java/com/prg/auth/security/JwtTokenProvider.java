package com.prg.auth.security;

import com.prg.auth.config.JwtConfig;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtTokenProvider {

    private final JwtConfig jwtConfig;
    private SecretKey secretKey;

    @PostConstruct
    public void init() {
        this.secretKey = Keys.hmacShaKeyFor(jwtConfig.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    public String generateAccessToken(UUID userId, UUID tenantId, String username, String email,
                                       List<String> roles, List<String> permissions, List<String> scopes) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + jwtConfig.getAccessTokenTtl() * 1000L);

        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(userId.toString())
                .claim("tenant_id", tenantId.toString())
                .claim("username", username)
                .claim("email", email)
                .claim("roles", roles)
                .claim("permissions", permissions)
                .claim("scopes", scopes)
                .issuer(jwtConfig.getIssuer())
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(secretKey, Jwts.SIG.HS256)
                .compact();
    }

    public String generateDeviceAccessToken(UUID userId, UUID tenantId, String username, String email,
                                               List<String> roles, List<String> permissions,
                                               List<String> scopes, UUID deviceId) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + jwtConfig.getAccessTokenTtl() * 1000L);

        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(userId.toString())
                .claim("tenant_id", tenantId.toString())
                .claim("username", username)
                .claim("email", email)
                .claim("roles", roles)
                .claim("permissions", permissions)
                .claim("scopes", scopes)
                .claim("device_id", deviceId.toString())
                .issuer(jwtConfig.getIssuer())
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(secretKey, Jwts.SIG.HS256)
                .compact();
    }

    public boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Invalid JWT token: {}", e.getMessage());
            return false;
        }
    }

    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public UUID getUserIdFromToken(String token) {
        Claims claims = parseToken(token);
        return UUID.fromString(claims.getSubject());
    }

    public UUID getTenantIdFromToken(String token) {
        Claims claims = parseToken(token);
        return UUID.fromString(claims.get("tenant_id", String.class));
    }

    @SuppressWarnings("unchecked")
    public UserPrincipal getUserPrincipalFromToken(String token) {
        Claims claims = parseToken(token);
        UUID userId = UUID.fromString(claims.getSubject());
        UUID tenantId = UUID.fromString(claims.get("tenant_id", String.class));
        String username = claims.get("username", String.class);
        String email = claims.get("email", String.class);
        List<String> roles = claims.get("roles", List.class);
        List<String> permissions = claims.get("permissions", List.class);
        List<String> scopes = claims.get("scopes", List.class);

        return UserPrincipal.builder()
                .userId(userId)
                .tenantId(tenantId)
                .username(username)
                .email(email)
                .roles(roles != null ? roles : List.of())
                .permissions(permissions != null ? permissions : List.of())
                .scopes(scopes != null ? scopes : List.of())
                .build();
    }

    public long getAccessTokenTtl() {
        return jwtConfig.getAccessTokenTtl();
    }
}
