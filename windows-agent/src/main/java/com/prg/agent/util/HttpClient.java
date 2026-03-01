package com.prg.agent.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.prg.agent.auth.AuthManager;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * OkHttp-based HTTP client wrapper with authentication support, automatic token refresh,
 * and configurable retry logic with exponential backoff.
 */
public class HttpClient {

    private static final Logger log = LoggerFactory.getLogger(HttpClient.class);
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient client;
    private final ObjectMapper objectMapper;
    private volatile AuthManager authManager;

    public HttpClient() {
        this.objectMapper = createObjectMapper();
        this.client = createOkHttpClient();
    }

    private ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }

    private OkHttpClient createOkHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .addInterceptor(new RetryInterceptor(3, Duration.ofSeconds(1)))
                .build();
    }

    /**
     * Sets the AuthManager for authenticated requests. Must be called before using authGet/authPost/authPut.
     */
    public void setAuthManager(AuthManager authManager) {
        this.authManager = authManager;
    }

    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }

    // ---- Unauthenticated requests (for login) ----

    /**
     * Sends an unauthenticated POST request.
     */
    public <T> T post(String url, Object body, Class<T> responseType) throws HttpException {
        String jsonBody = serialize(body);
        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(jsonBody, JSON_MEDIA_TYPE))
                .build();
        return execute(request, responseType);
    }

    /**
     * Sends an unauthenticated GET request.
     */
    public <T> T get(String url, Class<T> responseType) throws HttpException {
        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();
        return execute(request, responseType);
    }

    // ---- Authenticated requests (auto-adds Bearer token, auto-refreshes) ----

    /**
     * Sends an authenticated GET request.
     */
    public <T> T authGet(String url, Class<T> responseType) throws HttpException {
        String token = getValidToken();
        Request request = new Request.Builder()
                .url(url)
                .get()
                .addHeader("Authorization", "Bearer " + token)
                .build();
        return execute(request, responseType);
    }

    /**
     * Sends an authenticated POST request.
     */
    public <T> T authPost(String url, Object body, Class<T> responseType) throws HttpException {
        String token = getValidToken();
        String jsonBody = serialize(body);
        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(jsonBody, JSON_MEDIA_TYPE))
                .addHeader("Authorization", "Bearer " + token)
                .build();
        return execute(request, responseType);
    }

    /**
     * Sends an authenticated PUT request.
     */
    public <T> T authPut(String url, Object body, Class<T> responseType) throws HttpException {
        String token = getValidToken();
        String jsonBody = serialize(body);
        Request request = new Request.Builder()
                .url(url)
                .put(RequestBody.create(jsonBody, JSON_MEDIA_TYPE))
                .addHeader("Authorization", "Bearer " + token)
                .build();
        return execute(request, responseType);
    }

    /**
     * Uploads a file to a presigned S3 URL (no auth header needed, the URL contains auth).
     */
    public void uploadFile(String presignedUrl, File file, String contentType) throws HttpException {
        MediaType mediaType = MediaType.get(contentType);
        RequestBody fileBody = RequestBody.create(file, mediaType);

        Request request = new Request.Builder()
                .url(presignedUrl)
                .put(fileBody)
                .build();

        log.debug("Uploading file {} ({} bytes) to presigned URL", file.getName(), file.length());

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                throw new HttpException(response.code(), "File upload failed: " + response.code() + " " + responseBody);
            }
            log.debug("File upload successful: {} ({})", file.getName(), response.code());
        } catch (IOException e) {
            throw new HttpException(0, "File upload IO error: " + e.getMessage(), e);
        }
    }

    // ---- Internal methods ----

    private String getValidToken() throws HttpException {
        if (authManager == null) {
            throw new HttpException(0, "AuthManager not set, cannot make authenticated request");
        }
        try {
            return authManager.getValidAccessToken();
        } catch (Exception e) {
            throw new HttpException(401, "Failed to obtain valid access token: " + e.getMessage(), e);
        }
    }

    private <T> T execute(Request request, Class<T> responseType) throws HttpException {
        log.debug("{} {}", request.method(), request.url());

        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";

            if (!response.isSuccessful()) {
                log.warn("{} {} -> {} {}", request.method(), request.url(), response.code(), responseBody);
                throw new HttpException(response.code(), responseBody);
            }

            log.debug("{} {} -> {}", request.method(), request.url(), response.code());

            if (responseType == Void.class || responseType == void.class) {
                return null;
            }

            return objectMapper.readValue(responseBody, responseType);
        } catch (IOException e) {
            if (e instanceof HttpException) {
                throw (HttpException) e;
            }
            throw new HttpException(0, "Request failed: " + e.getMessage(), e);
        }
    }

    private String serialize(Object body) throws HttpException {
        if (body == null) {
            return "{}";
        }
        if (body instanceof String) {
            return (String) body;
        }
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new HttpException(0, "Failed to serialize request body: " + e.getMessage(), e);
        }
    }

    /**
     * OkHttp interceptor that implements retry with exponential backoff.
     */
    private static class RetryInterceptor implements Interceptor {
        private final int maxRetries;
        private final Duration initialDelay;

        RetryInterceptor(int maxRetries, Duration initialDelay) {
            this.maxRetries = maxRetries;
            this.initialDelay = initialDelay;
        }

        @Override
        public Response intercept(Chain chain) throws IOException {
            Request request = chain.request();
            IOException lastException = null;

            for (int attempt = 0; attempt <= maxRetries; attempt++) {
                try {
                    Response response = chain.proceed(request);

                    // Don't retry client errors (4xx), only server errors (5xx) and connection issues
                    if (response.isSuccessful() || (response.code() >= 400 && response.code() < 500)) {
                        return response;
                    }

                    // Server error - close response and retry
                    response.close();

                    if (attempt < maxRetries) {
                        long delayMs = initialDelay.toMillis() * (long) Math.pow(2, attempt);
                        log.warn("Request {} {} failed with {}, retrying in {}ms (attempt {}/{})",
                                request.method(), request.url(), response.code(),
                                delayMs, attempt + 1, maxRetries);
                        Thread.sleep(delayMs);
                    } else {
                        throw new IOException("Request failed after " + (maxRetries + 1) + " attempts: " + response.code());
                    }
                } catch (IOException e) {
                    lastException = e;

                    if (attempt < maxRetries) {
                        long delayMs = initialDelay.toMillis() * (long) Math.pow(2, attempt);
                        log.warn("Request {} {} failed with IO error, retrying in {}ms (attempt {}/{}): {}",
                                request.method(), request.url(),
                                delayMs, attempt + 1, maxRetries, e.getMessage());
                        try {
                            Thread.sleep(delayMs);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new IOException("Retry interrupted", ie);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Retry interrupted", e);
                }
            }

            throw lastException != null ? lastException : new IOException("Request failed after retries");
        }
    }

    /**
     * Exception for HTTP errors containing the status code and response body.
     */
    public static class HttpException extends IOException {
        private final int statusCode;

        public HttpException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }

        public HttpException(int statusCode, String message, Throwable cause) {
            super(message, cause);
            this.statusCode = statusCode;
        }

        public int getStatusCode() {
            return statusCode;
        }
    }
}
