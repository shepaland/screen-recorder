package com.prg.auth.service;

import com.prg.auth.config.MailruOAuthConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MailruOAuthClientTest {

    @Mock
    private RestTemplate restTemplate;

    private MailruOAuthClient mailruClient;
    private MailruOAuthConfig config;

    @BeforeEach
    void setUp() {
        config = new MailruOAuthConfig();
        config.setClientId("test-client-id");
        config.setClientSecret("test-client-secret");
        config.setCallbackUrl("http://localhost:8081/api/v1/auth/oauth/mailru/callback");
        config.setTokenUrl("https://oauth.mail.ru/token");
        config.setUserInfoUrl("https://oauth.mail.ru/userinfo");

        mailruClient = new MailruOAuthClient(config);
        // Inject mock RestTemplate
        ReflectionTestUtils.setField(mailruClient, "restTemplate", restTemplate);
    }

    @Test
    @DisplayName("exchangeCodeForToken should POST with correct parameters including redirect_uri")
    @SuppressWarnings("unchecked")
    void testExchangeCodeForToken() {
        Map<String, Object> responseBody = Map.of(
                "access_token", "test-access-token",
                "token_type", "bearer",
                "expires_in", 3600
        );
        ResponseEntity<Map> responseEntity = new ResponseEntity<>(responseBody, HttpStatus.OK);

        when(restTemplate.exchange(
                eq("https://oauth.mail.ru/token"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(Map.class)
        )).thenReturn(responseEntity);

        String token = mailruClient.exchangeCodeForToken("test-code");

        assertThat(token).isEqualTo("test-access-token");

        // Verify the request body contains redirect_uri (Mail.ru requirement)
        ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(
                eq("https://oauth.mail.ru/token"),
                eq(HttpMethod.POST),
                entityCaptor.capture(),
                eq(Map.class)
        );

        HttpEntity<MultiValueMap<String, String>> capturedEntity = entityCaptor.getValue();
        MultiValueMap<String, String> body = capturedEntity.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getFirst("grant_type")).isEqualTo("authorization_code");
        assertThat(body.getFirst("code")).isEqualTo("test-code");
        assertThat(body.getFirst("client_id")).isEqualTo("test-client-id");
        assertThat(body.getFirst("client_secret")).isEqualTo("test-client-secret");
        assertThat(body.getFirst("redirect_uri")).isEqualTo("http://localhost:8081/api/v1/auth/oauth/mailru/callback");
    }

    @Test
    @DisplayName("exchangeCodeForToken should throw on empty response")
    @SuppressWarnings("unchecked")
    void testExchangeCodeForTokenEmptyResponse() {
        ResponseEntity<Map> responseEntity = new ResponseEntity<>(Map.of(), HttpStatus.OK);

        when(restTemplate.exchange(
                anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)
        )).thenReturn(responseEntity);

        assertThatThrownBy(() -> mailruClient.exchangeCodeForToken("test-code"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("empty response");
    }

    @Test
    @DisplayName("exchangeCodeForToken should throw on RestClientException")
    void testExchangeCodeForTokenNetworkError() {
        when(restTemplate.exchange(
                anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)
        )).thenThrow(new RestClientException("Connection refused"));

        assertThatThrownBy(() -> mailruClient.exchangeCodeForToken("test-code"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to exchange authorization code");
    }

    @Test
    @DisplayName("getUserInfo should use Bearer auth (not OAuth) and return user info map")
    @SuppressWarnings("unchecked")
    void testGetUserInfo() {
        Map<String, Object> userInfo = Map.of(
                "id", "12345",
                "email", "user@mail.ru",
                "name", "Test User",
                "image", "https://filin.mail.ru/pic?d=test"
        );
        ResponseEntity<Map> responseEntity = new ResponseEntity<>(userInfo, HttpStatus.OK);

        when(restTemplate.exchange(
                eq("https://oauth.mail.ru/userinfo"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(Map.class)
        )).thenReturn(responseEntity);

        Map<String, Object> result = mailruClient.getUserInfo("test-token");

        assertThat(result).containsEntry("id", "12345");
        assertThat(result).containsEntry("email", "user@mail.ru");
        assertThat(result).containsEntry("name", "Test User");
        assertThat(result).containsEntry("image", "https://filin.mail.ru/pic?d=test");

        // Verify Bearer auth header (not "OAuth" like Yandex)
        ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(
                eq("https://oauth.mail.ru/userinfo"),
                eq(HttpMethod.GET),
                entityCaptor.capture(),
                eq(Map.class)
        );

        HttpEntity<Void> capturedEntity = entityCaptor.getValue();
        String authHeader = capturedEntity.getHeaders().getFirst("Authorization");
        assertThat(authHeader).isEqualTo("Bearer test-token");
        assertThat(authHeader).doesNotStartWith("OAuth ");
    }

    @Test
    @DisplayName("getUserInfo should throw on missing id in response")
    @SuppressWarnings("unchecked")
    void testGetUserInfoMissingId() {
        Map<String, Object> userInfo = Map.of("email", "user@mail.ru");
        ResponseEntity<Map> responseEntity = new ResponseEntity<>(userInfo, HttpStatus.OK);

        when(restTemplate.exchange(
                anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)
        )).thenReturn(responseEntity);

        assertThatThrownBy(() -> mailruClient.getUserInfo("test-token"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("empty response");
    }
}
