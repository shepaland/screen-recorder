package com.prg.auth.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "prg.email-otp")
public class EmailOtpConfig {

    /** OTP code TTL in seconds (default 600 = 10 minutes) */
    private int codeTtl = 600;

    /** OTP code length (default 6 digits) */
    private int codeLength = 6;

    /** Max verify attempts per code before blocking (default 10) */
    private int maxVerifyAttempts = 10;

    /** Block duration in seconds after max attempts (default 1800 = 30 minutes) */
    private int blockDuration = 1800;

    /** Cooldown between sends for the same email in seconds (default 60) */
    private int sendCooldown = 60;

    /** Max number of sends in the send window for the same email (default 5) */
    private int sendWindowMax = 5;

    /** Send window duration in seconds (default 1800 = 30 minutes) */
    private int sendWindowDuration = 1800;

    /** Max requests from one IP in the burst window (default 20) */
    private int ipBurstMax = 20;

    /** IP burst window duration in seconds (default 300 = 5 minutes) */
    private int ipBurstWindow = 300;

    /** HMAC secret for code hashing (MUST be set via env/K8s secret, min 32 chars) */
    private String hmacSecret;

    /** Max tenants per email address (default 10) */
    private int maxTenantsPerEmail = 10;
}
