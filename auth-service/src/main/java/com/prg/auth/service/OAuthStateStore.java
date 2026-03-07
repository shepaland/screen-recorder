package com.prg.auth.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * Server-side store for OAuth state parameters.
 * Uses Caffeine TTL cache (10 min expiry) to validate state on callback,
 * preventing CSRF attacks.
 */
@Component
public class OAuthStateStore {

    private final Cache<String, Boolean> stateCache;

    public OAuthStateStore() {
        this.stateCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(10))
                .maximumSize(10_000)
                .build();
    }

    /**
     * Generate a new random state parameter and store it in the cache.
     *
     * @return generated state string
     */
    public String generateAndStore() {
        String state = UUID.randomUUID().toString();
        stateCache.put(state, Boolean.TRUE);
        return state;
    }

    /**
     * Validate a state parameter received in OAuth callback.
     * If valid, the state is consumed (removed from cache) to prevent replay.
     *
     * @param state the state parameter from the callback
     * @return true if the state is valid and was consumed; false otherwise
     */
    public boolean validateAndConsume(String state) {
        if (state == null || state.isBlank()) {
            return false;
        }
        Boolean found = stateCache.getIfPresent(state);
        if (found != null) {
            stateCache.invalidate(state);
            return true;
        }
        return false;
    }
}
