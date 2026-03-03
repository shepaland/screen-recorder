package com.prg.agent.config;

import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Agent configuration loaded from agent.properties and optionally overridden
 * by server-provided settings after device login.
 */
@Getter
@Setter
public class AgentConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentConfig.class);

    // Server URLs
    private String serverBaseUrl;
    private String serverAuthUrl;
    private String serverControlUrl;
    private String serverIngestUrl;

    // Data directories
    private String dataDir;
    private String logDir;
    private String segmentsDir;

    // Buffer limits
    private int maxBufferMb;
    private int uploadThreads;
    private int uploadRetryMax;

    // Capture settings (can be overridden by server)
    private int captureFps;
    private int segmentDurationSec;
    private String captureQuality;

    // FFmpeg
    private String ffmpegPath;

    public AgentConfig() {
        loadDefaults();
        loadFromClasspath();
        resolveVariables();
        ensureDirectories();
    }

    private void loadDefaults() {
        serverBaseUrl = "https://localhost";
        serverAuthUrl = serverBaseUrl + "/api/v1/auth";
        serverControlUrl = serverBaseUrl + "/api/v1/devices";
        serverIngestUrl = serverBaseUrl + "/api/v1/ingest";

        String userHome = System.getProperty("user.home");
        dataDir = userHome + File.separator + ".prg-agent";
        logDir = dataDir + File.separator + "logs";
        segmentsDir = dataDir + File.separator + "segments";

        maxBufferMb = 2048;
        uploadThreads = 2;
        uploadRetryMax = 3;

        captureFps = 5;
        segmentDurationSec = 10;
        captureQuality = "medium";

        ffmpegPath = "";
    }

    private void loadFromClasspath() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("agent.properties")) {
            if (is == null) {
                log.warn("agent.properties not found on classpath, using defaults");
                return;
            }
            Properties props = new Properties();
            props.load(is);
            applyProperties(props);
            log.info("Loaded configuration from agent.properties");
        } catch (IOException e) {
            log.error("Failed to load agent.properties", e);
        }
    }

    /**
     * Loads configuration from an external file (e.g., install directory).
     */
    public void loadFromFile(Path configFile) {
        if (!Files.exists(configFile)) {
            log.warn("Config file not found: {}", configFile);
            return;
        }
        try (InputStream is = Files.newInputStream(configFile)) {
            Properties props = new Properties();
            props.load(is);
            applyProperties(props);
            resolveVariables();
            log.info("Loaded configuration from {}", configFile);
        } catch (IOException e) {
            log.error("Failed to load config from {}", configFile, e);
        }
    }

    private void applyProperties(Properties props) {
        serverBaseUrl = props.getProperty("server.base.url", serverBaseUrl);
        serverAuthUrl = props.getProperty("server.auth.url", serverBaseUrl + "/api/v1/auth");
        serverControlUrl = props.getProperty("server.control.url", serverBaseUrl + "/api/v1/devices");
        serverIngestUrl = props.getProperty("server.ingest.url", serverBaseUrl + "/api/v1/ingest");

        dataDir = props.getProperty("agent.data.dir", dataDir);
        logDir = props.getProperty("agent.log.dir", logDir);
        segmentsDir = props.getProperty("agent.segments.dir", segmentsDir);

        maxBufferMb = Integer.parseInt(props.getProperty("agent.max.buffer.mb", String.valueOf(maxBufferMb)));
        uploadThreads = Integer.parseInt(props.getProperty("agent.upload.threads", String.valueOf(uploadThreads)));
        uploadRetryMax = Integer.parseInt(props.getProperty("agent.upload.retry.max", String.valueOf(uploadRetryMax)));

        captureFps = Integer.parseInt(props.getProperty("agent.capture.fps", String.valueOf(captureFps)));
        segmentDurationSec = Integer.parseInt(props.getProperty("agent.capture.segment.duration.sec", String.valueOf(segmentDurationSec)));
        captureQuality = props.getProperty("agent.capture.quality", captureQuality);

        ffmpegPath = props.getProperty("agent.ffmpeg.path", ffmpegPath);
    }

    private void resolveVariables() {
        String userHome = System.getProperty("user.home");
        dataDir = dataDir.replace("${user.home}", userHome);
        logDir = logDir.replace("${user.home}", userHome);
        segmentsDir = segmentsDir.replace("${user.home}", userHome);

        // Resolve ${server.base.url} in derived URLs
        serverAuthUrl = serverAuthUrl.replace("${server.base.url}", serverBaseUrl);
        serverControlUrl = serverControlUrl.replace("${server.base.url}", serverBaseUrl);
        serverIngestUrl = serverIngestUrl.replace("${server.base.url}", serverBaseUrl);
    }

    private void ensureDirectories() {
        try {
            Files.createDirectories(Path.of(dataDir));
            Files.createDirectories(Path.of(logDir));
            Files.createDirectories(Path.of(segmentsDir));
            log.debug("Ensured data directories exist: {}", dataDir);
        } catch (IOException e) {
            log.error("Failed to create data directories", e);
        }
    }

    /**
     * Apply server-provided configuration from device-login response.
     */
    public void applyServerConfig(ServerConfig serverConfig) {
        if (serverConfig == null) {
            return;
        }
        if (serverConfig.getHeartbeatIntervalSec() > 0) {
            log.info("Server config: heartbeat_interval_sec={}", serverConfig.getHeartbeatIntervalSec());
        }
        if (serverConfig.getSegmentDurationSec() > 0) {
            this.segmentDurationSec = serverConfig.getSegmentDurationSec();
            log.info("Server config: segment_duration_sec={}", segmentDurationSec);
        }
        if (serverConfig.getCaptureFps() > 0) {
            this.captureFps = serverConfig.getCaptureFps();
            log.info("Server config: capture_fps={}", captureFps);
        }
        if (serverConfig.getQuality() != null && !serverConfig.getQuality().isEmpty()) {
            this.captureQuality = serverConfig.getQuality();
            log.info("Server config: quality={}", captureQuality);
        }
        if (serverConfig.getIngestBaseUrl() != null && !serverConfig.getIngestBaseUrl().isEmpty()) {
            this.serverIngestUrl = serverConfig.getIngestBaseUrl();
            log.info("Server config: ingest_base_url={}", serverIngestUrl);
        }
        if (serverConfig.getControlPlaneBaseUrl() != null && !serverConfig.getControlPlaneBaseUrl().isEmpty()) {
            this.serverControlUrl = serverConfig.getControlPlaneBaseUrl();
            log.info("Server config: control_plane_base_url={}", serverControlUrl);
        }
    }

    /**
     * Returns the resolved FFmpeg executable path. If not set, defaults to "ffmpeg" (on PATH).
     */
    public String getResolvedFfmpegPath() {
        if (ffmpegPath == null || ffmpegPath.isBlank()) {
            return "ffmpeg";
        }
        return ffmpegPath;
    }
}
