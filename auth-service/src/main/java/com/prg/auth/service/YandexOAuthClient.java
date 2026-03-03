package com.prg.auth.service;

import com.prg.auth.config.YandexOAuthConfig;
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
public class YandexOAuthClient {

    private final YandexOAuthConfig config;
    private final RestTemplate restTemplate;

    public YandexOAuthClient(YandexOAuthConfig config) {
        this.config = config;
        this.restTemplate = new RestTemplate();
    }

    /**
     * Exchange authorization code for an access token via Yandex OAuth token endpoint.
     *
     * @param code authorization code from Yandex callback
     * @return Yandex access token
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
                log.error("Yandex token exchange returned empty or invalid response");
                throw new RuntimeException("Failed to exchange code for token: empty response");
            }

            String accessToken = (String) responseBody.get("access_token");
            log.debug("Successfully exchanged code for Yandex access token");
            return accessToken;

        } catch (RestClientException e) {
            log.error("Failed to exchange code for Yandex token: {}", e.getMessage());
            throw new RuntimeException("Failed to exchange authorization code for token", e);
        }
    }

    /**
     * Get user info from Yandex Login API using the access token.
     *
     * @param accessToken Yandex access token
     * @return user info map containing id, default_email, display_name, first_name, last_name, etc.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getUserInfo(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "OAuth " + accessToken);

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
                log.error("Yandex user info returned empty or invalid response");
                throw new RuntimeException("Failed to get user info: empty response");
            }

            log.debug("Successfully retrieved Yandex user info for id={}", userInfo.get("id"));
            return userInfo;

        } catch (RestClientException e) {
            log.error("Failed to get Yandex user info: {}", e.getMessage());
            throw new RuntimeException("Failed to get user info from Yandex", e);
        }
    }
}
