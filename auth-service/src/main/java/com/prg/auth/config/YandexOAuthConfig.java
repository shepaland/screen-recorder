package com.prg.auth.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "prg.oauth.yandex")
public class YandexOAuthConfig {

    private String clientId;
    private String clientSecret;
    private String authorizeUrl = "https://oauth.yandex.ru/authorize";
    private String tokenUrl = "https://oauth.yandex.ru/token";
    private String userInfoUrl = "https://login.yandex.ru/info?format=json";
    private String callbackUrl;
}
