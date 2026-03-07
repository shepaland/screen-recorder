package com.prg.auth.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.prg.auth.exception.RateLimitExceededException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Rate limiter for OAuth endpoints using Caffeine cache.
 * Separate from AuthService rate limiter to have different thresholds.
 */
@Slf4j
@Component
public class OAuthRateLimiter {

    // OAuth callback: max 10 attempts per 5 minutes per IP
    private static final int CALLBACK_MAX_ATTEMPTS = 10;
    private static final long CALLBACK_WINDOW_MS = 5 * 60 * 1000L;

    // OAuth onboarding: max 5 attempts per 10 minutes per IP
    private static final int ONBOARDING_MAX_ATTEMPTS = 5;
    private static final long ONBOARDING_WINDOW_MS = 10 * 60 * 1000L;

    private final Cache<String, List<Long>> callbackAttempts;
    private final Cache<String, List<Long>> onboardingAttempts;

    public OAuthRateLimiter() {
        this.callbackAttempts = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(6))
                .maximumSize(50_000)
                .build();

        this.onboardingAttempts = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(11))
                .maximumSize(50_000)
                .build();
    }

    /**
     * Check and record a callback attempt from the given IP.
     * Throws RateLimitExceededException if limit is exceeded.
     */
    public void checkCallbackRateLimit(String ipAddress) {
        checkLimit(callbackAttempts, "oauth-callback:" + ipAddress,
                CALLBACK_MAX_ATTEMPTS, CALLBACK_WINDOW_MS,
                "Too many OAuth callback attempts. Please try again later.");
    }

    /**
     * Check and record an onboarding attempt from the given IP.
     * Throws RateLimitExceededException if limit is exceeded.
     */
    public void checkOnboardingRateLimit(String ipAddress) {
        checkLimit(onboardingAttempts, "oauth-onboarding:" + ipAddress,
                ONBOARDING_MAX_ATTEMPTS, ONBOARDING_WINDOW_MS,
                "Too many onboarding attempts. Please try again later.");
    }

    private void checkLimit(Cache<String, List<Long>> cache, String key,
                            int maxAttempts, long windowMs, String message) {
        long now = System.currentTimeMillis();
        long windowStart = now - windowMs;

        List<Long> attempts = cache.get(key, k -> Collections.synchronizedList(new CopyOnWriteArrayList<>()));
        if (attempts == null) {
            attempts = Collections.synchronizedList(new CopyOnWriteArrayList<>());
            cache.put(key, attempts);
        }

        // Clean old entries
        attempts.removeIf(ts -> ts < windowStart);

        if (attempts.size() >= maxAttempts) {
            log.warn("OAuth rate limit exceeded for key={}", key);
            throw new RateLimitExceededException(message);
        }

        attempts.add(now);
    }
}
