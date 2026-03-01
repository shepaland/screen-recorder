package com.prg.agent.auth;

import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/**
 * In-memory store for the current access and refresh tokens.
 *
 * <p>The access token is kept in memory only. The refresh token is also
 * persisted to the CredentialStore for recovery after restart.
 * Thread-safe via volatile fields and synchronized mutation.
 */
@Getter
public class TokenStore {

    private static final Logger log = LoggerFactory.getLogger(TokenStore.class);

    private volatile String accessToken;
    private volatile String refreshToken;
    private volatile Instant accessTokenExpiresAt;
    private volatile String deviceId;

    /**
     * Updates both tokens after a successful login or refresh.
     */
    public synchronized void updateTokens(String accessToken, String refreshToken,
                                           int expiresInSeconds, String deviceId) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.accessTokenExpiresAt = Instant.now().plusSeconds(expiresInSeconds);
        this.deviceId = deviceId;
        log.debug("Tokens updated, access token expires at {}", accessTokenExpiresAt);
    }

    /**
     * Updates only the access and refresh tokens (after refresh), keeping deviceId.
     */
    public synchronized void updateTokens(String accessToken, String refreshToken, int expiresInSeconds) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.accessTokenExpiresAt = Instant.now().plusSeconds(expiresInSeconds);
        log.debug("Tokens refreshed, access token expires at {}", accessTokenExpiresAt);
    }

    /**
     * Checks if the access token will expire within the given number of seconds.
     */
    public boolean isAccessTokenExpiringSoon(int thresholdSeconds) {
        if (accessTokenExpiresAt == null) {
            return true;
        }
        return Instant.now().plusSeconds(thresholdSeconds).isAfter(accessTokenExpiresAt);
    }

    /**
     * Checks if we have a valid (non-null, non-expired) access token.
     */
    public boolean hasValidAccessToken() {
        return accessToken != null && !isAccessTokenExpiringSoon(0);
    }

    /**
     * Clears all stored tokens.
     */
    public synchronized void clear() {
        this.accessToken = null;
        this.refreshToken = null;
        this.accessTokenExpiresAt = null;
        this.deviceId = null;
        log.debug("Token store cleared");
    }

    /**
     * Returns true if we have any tokens (even if expired).
     */
    public boolean hasTokens() {
        return accessToken != null || refreshToken != null;
    }
}
