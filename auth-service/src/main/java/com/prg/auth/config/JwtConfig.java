package com.prg.auth.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "prg.jwt")
public class JwtConfig {
    private String secret;
    private long accessTokenTtl;
    private long refreshTokenTtl;
    private String issuer;
}
