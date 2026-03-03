package com.prg.auth.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "prg.frontend")
public class FrontendConfig {

    private String baseUrl = "http://localhost:3000";
}
