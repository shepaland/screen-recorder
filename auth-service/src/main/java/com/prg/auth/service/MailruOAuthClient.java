package com.prg.auth.service;

import com.prg.auth.config.MailruOAuthConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Slf4j
@Service
public class MailruOAuthClient {

    private final MailruOAuthConfig config;
    private final RestTemplate restTemplate;

    public MailruOAuthClient(MailruOAuthConfig config) {
        this.config = config;
        this.restTemplate = new RestTemplate();
    }

    /**
     * Exchange authorization code for an access token via Mail.ru OAuth token endpoint.
     * Note: redirect_uri is REQUIRED for Mail.ru (unlike Yandex).
     *
     * @param code authorization code from Mail.ru callback
     * @return Mail.ru access token
     */
    @SuppressWarnings("unchecked")
    public String exchangeCodeForToken(String code) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "authorization_code");
        body.add("code", code);
        body.add("client_id", config.getClientId());
        body.add("client_secret", config.getClientSecret());
        body.add("redirect_uri", config.getCallbackUrl());

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    config.getTokenUrl(),
                    HttpMethod.POST,
                    request,
                    Map.class
            );

            Map<String, Object> responseBody = response.getBody();
            if (responseBody == null || !responseBody.containsKey("access_token")) {
                log.error("Mail.ru token exchange returned empty or invalid response");
                throw new RuntimeException("Failed to exchange code for token: empty response");
            }

            String accessToken = (String) responseBody.get("access_token");
            log.debug("Successfully exchanged code for Mail.ru access token");
            return accessToken;

        } catch (RestClientException e) {
            log.error("Failed to exchange code for Mail.ru token: {}", e.getMessage());
            throw new RuntimeException("Failed to exchange authorization code for token", e);
        }
    }

    /**
     * Get user info from Mail.ru OAuth userinfo endpoint.
     * Note: Mail.ru uses "Bearer" auth header (not "OAuth" like Yandex).
     *
     * @param accessToken Mail.ru access token
     * @return user info map containing id, email, name, image, etc.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getUserInfo(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + accessToken);

        HttpEntity<Void> request = new HttpEntity<>(headers);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    config.getUserInfoUrl(),
                    HttpMethod.GET,
                    request,
                    Map.class
            );

            Map<String, Object> userInfo = response.getBody();
            if (userInfo == null || !userInfo.containsKey("id")) {
                log.error("Mail.ru user info returned empty or invalid response");
                throw new RuntimeException("Failed to get user info: empty response");
            }

            log.debug("Successfully retrieved Mail.ru user info for id={}", userInfo.get("id"));
            return userInfo;

        } catch (RestClientException e) {
            log.error("Failed to get Mail.ru user info: {}", e.getMessage());
            throw new RuntimeException("Failed to get user info from Mail.ru", e);
        }
    }

    public MailruOAuthConfig getConfig() {
        return config;
    }
}
