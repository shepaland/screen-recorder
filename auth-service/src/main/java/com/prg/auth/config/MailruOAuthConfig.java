package com.prg.auth.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "prg.oauth.mailru")
public class MailruOAuthConfig {

    private String clientId;
    private String clientSecret;
    private String authorizeUrl = "https://oauth.mail.ru/login";
    private String tokenUrl = "https://oauth.mail.ru/token";
    private String userInfoUrl = "https://oauth.mail.ru/userinfo";
    private String callbackUrl;
    private String scope = "userinfo";
    private boolean enabled = false;
}
