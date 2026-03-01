package com.prg.agent.upload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.prg.agent.config.AgentConfig;
import com.prg.agent.util.HttpClient;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages recording session lifecycle: creation, tracking stats, and completion.
 *
 * <p>A recording session represents a continuous period of screen capture.
 * Sessions are created on the server (ingest-gateway) and tracked locally
 * for statistics and correlation of segment uploads.
 */
public class SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    private final AgentConfig config;
    private final HttpClient httpClient;

    @Getter
    private volatile String currentSessionId;
    @Getter
    private volatile Instant sessionStartedAt;
    private final AtomicInteger segmentsSent = new AtomicInteger(0);
    private final AtomicLong bytesSent = new AtomicLong(0);
    @Getter
    private volatile Instant lastUploadTime;

    public SessionManager(AgentConfig config, HttpClient httpClient) {
        this.config = config;
        this.httpClient = httpClient;
    }

    /**
     * Creates a new recording session on the server.
     *
     * @param deviceId the device ID
     * @param metadata session metadata (resolution, fps, codec, etc.)
     * @return the server-assigned session ID
     */
    public String startSession(String deviceId, Map<String, Object> metadata) throws HttpClient.HttpException {
        log.info("Creating recording session for device {}", deviceId);

        CreateSessionRequest request = new CreateSessionRequest();
        request.setDeviceId(deviceId);
        request.setMetadata(metadata != null ? metadata : Map.of());

        String url = config.getServerIngestUrl() + "/sessions";
        CreateSessionResponse response = httpClient.authPost(url, request, CreateSessionResponse.class);

        this.currentSessionId = response.getSessionId();
        this.sessionStartedAt = Instant.now();
        this.segmentsSent.set(0);
        this.bytesSent.set(0);

        log.info("Recording session created: {} (status={})", currentSessionId, response.getStatus());
        return currentSessionId;
    }

    /**
     * Ends the current recording session on the server.
     */
    public void endSession() {
        String sessionId = currentSessionId;
        if (sessionId == null) {
            log.debug("No active session to end");
            return;
        }

        try {
            String url = config.getServerIngestUrl() + "/sessions/" + sessionId + "/end";
            httpClient.authPut(url, Map.of(), Void.class);
            log.info("Recording session ended: {} (segments={}, bytes={})",
                    sessionId, segmentsSent.get(), bytesSent.get());
        } catch (Exception e) {
            log.error("Failed to end session {} on server", sessionId, e);
        }

        this.currentSessionId = null;
        this.sessionStartedAt = null;
    }

    /**
     * Records that a segment was successfully uploaded.
     */
    public void recordSegmentSent(long sizeBytes) {
        segmentsSent.incrementAndGet();
        bytesSent.addAndGet(sizeBytes);
        lastUploadTime = Instant.now();
    }

    /**
     * Returns current session statistics.
     */
    public SessionStats getStats() {
        SessionStats stats = new SessionStats();
        stats.setSessionId(currentSessionId);
        stats.setSegmentsSent(segmentsSent.get());
        stats.setBytesSent(bytesSent.get());

        if (sessionStartedAt != null) {
            stats.setDuration(Duration.between(sessionStartedAt, Instant.now()));
        } else {
            stats.setDuration(Duration.ZERO);
        }

        return stats;
    }

    /**
     * Returns true if there is an active recording session.
     */
    public boolean hasActiveSession() {
        return currentSessionId != null;
    }

    // ---- DTOs ----

    @Data
    @NoArgsConstructor
    public static class CreateSessionRequest {
        @JsonProperty("device_id")
        private String deviceId;
        private Map<String, Object> metadata;
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CreateSessionResponse {
        @JsonProperty("session_id")
        private String sessionId;
        private String status;
        @JsonProperty("started_ts")
        private String startedTs;
    }

    /**
     * Session statistics snapshot.
     */
    @Data
    public static class SessionStats {
        private String sessionId;
        private int segmentsSent;
        private long bytesSent;
        private Duration duration;

        /**
         * Returns formatted duration as HH:MM:SS.
         */
        public String getFormattedDuration() {
            if (duration == null) return "00:00:00";
            long seconds = duration.getSeconds();
            return String.format("%02d:%02d:%02d",
                    seconds / 3600, (seconds % 3600) / 60, seconds % 60);
        }

        /**
         * Returns bytes sent formatted as human-readable size.
         */
        public String getFormattedBytesSent() {
            if (bytesSent < 1024) return bytesSent + " B";
            if (bytesSent < 1024 * 1024) return String.format("%.1f KB", bytesSent / 1024.0);
            if (bytesSent < 1024L * 1024 * 1024) return String.format("%.1f MB", bytesSent / (1024.0 * 1024));
            return String.format("%.2f GB", bytesSent / (1024.0 * 1024 * 1024));
        }
    }
}
