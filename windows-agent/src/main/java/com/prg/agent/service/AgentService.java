package com.prg.agent.service;

import com.prg.agent.AgentState;
import com.prg.agent.auth.AuthManager;
import com.prg.agent.auth.CredentialStore;
import com.prg.agent.capture.ScreenCaptureManager;
import com.prg.agent.capture.SegmentProducer;
import com.prg.agent.command.CommandHandler;
import com.prg.agent.command.HeartbeatScheduler;
import com.prg.agent.config.AgentConfig;
import com.prg.agent.storage.LocalDatabase;
import com.prg.agent.storage.SegmentFileManager;
import com.prg.agent.ui.TrayManager;
import com.prg.agent.upload.SegmentUploader;
import com.prg.agent.upload.SessionManager;
import com.prg.agent.upload.UploadQueue;
import com.prg.agent.util.CryptoUtil;
import com.prg.agent.util.HardwareId;
import com.prg.agent.util.HttpClient;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Main coordinator for the agent application.
 *
 * <p>Manages the lifecycle of all agent components:
 * authentication, heartbeat, capture, upload, and command processing.
 *
 * <p>Acts as the central state machine, transitioning between states
 * (NOT_AUTHENTICATED -> DISCONNECTED -> ONLINE -> RECORDING) and
 * notifying listeners of state changes.
 */
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    @Getter
    private volatile AgentState state = AgentState.NOT_AUTHENTICATED;

    // Components
    @Getter
    private final AgentConfig config;
    private final CryptoUtil cryptoUtil;
    private final HttpClient httpClient;
    private final CredentialStore credentialStore;
    @Getter
    private final AuthManager authManager;
    private final LocalDatabase localDatabase;
    private final SegmentFileManager segmentFileManager;
    private final MetricsCollector metricsCollector;
    private final CommandHandler commandHandler;
    private final HeartbeatScheduler heartbeatScheduler;
    private final ScreenCaptureManager captureManager;
    private final SessionManager sessionManager;
    private final SegmentUploader segmentUploader;
    private final UploadQueue uploadQueue;
    private final SegmentProducer segmentProducer;

    // UI
    private volatile TrayManager trayManager;

    // Listeners
    private final List<StateChangeListener> stateListeners = new CopyOnWriteArrayList<>();

    /**
     * Functional interface for state change notifications.
     */
    @FunctionalInterface
    public interface StateChangeListener {
        void onStateChanged(AgentState newState);
    }

    public AgentService() {
        // Initialize configuration
        this.config = new AgentConfig();

        // Initialize crypto
        this.cryptoUtil = new CryptoUtil();

        // Initialize HTTP client
        this.httpClient = new HttpClient();

        // Initialize credential store
        this.credentialStore = new CredentialStore(config, cryptoUtil, httpClient.getObjectMapper());

        // Initialize auth manager
        this.authManager = new AuthManager(config, httpClient, credentialStore);
        this.httpClient.setAuthManager(authManager);

        // Initialize storage
        this.localDatabase = new LocalDatabase(config);
        this.segmentFileManager = new SegmentFileManager(config);

        // Initialize metrics
        this.metricsCollector = new MetricsCollector();

        // Initialize command handler
        this.commandHandler = new CommandHandler(config, httpClient);
        this.commandHandler.setAgentService(this);

        // Initialize heartbeat
        this.heartbeatScheduler = new HeartbeatScheduler(config, httpClient, metricsCollector, commandHandler);
        this.heartbeatScheduler.setAgentService(this);

        // Initialize upload pipeline
        this.sessionManager = new SessionManager(config, httpClient);
        this.segmentUploader = new SegmentUploader(config, httpClient, sessionManager);
        this.uploadQueue = new UploadQueue(config, segmentUploader, localDatabase);

        // Initialize capture
        this.captureManager = new ScreenCaptureManager(config, segmentFileManager);
        this.segmentProducer = new SegmentProducer(segmentFileManager, uploadQueue);
        this.captureManager.setOnNewSegment(segmentProducer::onNewSegment);

        log.info("AgentService constructed, hardware_id={}...", HardwareId.getHardwareId().substring(0, 8));
    }

    /**
     * Sets the tray manager reference (called from UI thread).
     */
    public void setTrayManager(TrayManager trayManager) {
        this.trayManager = trayManager;
    }

    /**
     * Initializes the agent: tries to restore saved credentials and connect.
     */
    public void initialize() {
        log.info("Initializing agent service...");

        // Start upload queue
        uploadQueue.start();

        // Try to restore saved session
        boolean restored = authManager.tryLoadSavedCredentials();
        if (restored) {
            log.info("Session restored from saved credentials");
            setState(AgentState.ONLINE);
            heartbeatScheduler.start();

            // Retry any pending segments from local database
            uploadQueue.retryPendingSegments();
        } else {
            log.info("No saved session, waiting for login");
            setState(AgentState.NOT_AUTHENTICATED);

            // Show config window automatically if in interactive mode
            if (trayManager != null) {
                trayManager.showConfigWindow();
            }
        }

        log.info("Agent service initialized (state={})", state);
    }

    /**
     * Performs device login with the given credentials.
     */
    public void login(String registrationToken, String username, String password) throws Exception {
        log.info("Login requested for user '{}'", username);

        try {
            AuthManager.DeviceLoginResponse response =
                    authManager.login(registrationToken, username, password);

            setState(AgentState.ONLINE);

            // Start heartbeat
            heartbeatScheduler.start();

            // Show status panel in config window
            if (trayManager != null && trayManager.getConfigWindow() != null) {
                trayManager.getConfigWindow().showStatus();
            }

            log.info("Login successful. Device ID: {}, tenant: {}",
                    response.getDeviceId(), authManager.getTenantName());

        } catch (AuthManager.AuthException e) {
            setState(AgentState.NOT_AUTHENTICATED);
            throw e;
        }
    }

    /**
     * Logs out the agent, clearing all credentials and stopping services.
     */
    public void logout() {
        log.info("Logout requested");
        stopRecording();
        heartbeatScheduler.stop();
        authManager.logout();
        setState(AgentState.NOT_AUTHENTICATED);

        if (trayManager != null && trayManager.getConfigWindow() != null) {
            trayManager.getConfigWindow().showLogin();
        }
    }

    /**
     * Starts a screen recording session.
     */
    public void startRecording() {
        if (state == AgentState.RECORDING) {
            log.warn("Already recording");
            return;
        }

        if (!authManager.isAuthenticated()) {
            log.error("Cannot start recording: not authenticated");
            return;
        }

        try {
            // Create session on server
            String deviceId = authManager.getDeviceId();
            Map<String, Object> metadata = Map.of(
                    "resolution", "1920x1080",
                    "fps", config.getCaptureFps(),
                    "codec", "h264",
                    "quality", config.getCaptureQuality()
            );

            String sessionId = sessionManager.startSession(deviceId, metadata);
            log.info("Recording session created: {}", sessionId);

            // Start segment producer
            segmentProducer.startSession(sessionId);

            // Start screen capture
            captureManager.startRecording(sessionId);

            setState(AgentState.RECORDING);
            log.info("Recording started for session {}", sessionId);

        } catch (Exception e) {
            log.error("Failed to start recording", e);
            throw new RuntimeException("Failed to start recording: " + e.getMessage(), e);
        }
    }

    /**
     * Stops the current screen recording session.
     */
    public void stopRecording() {
        if (state != AgentState.RECORDING) {
            log.debug("Not recording, nothing to stop");
            return;
        }

        log.info("Stopping recording...");

        try {
            // Stop capture first
            captureManager.stopRecording();

            // Stop segment producer
            segmentProducer.stopSession();

            // End session on server
            sessionManager.endSession();

            if (authManager.isAuthenticated()) {
                setState(AgentState.ONLINE);
            } else {
                setState(AgentState.DISCONNECTED);
            }

            log.info("Recording stopped");
        } catch (Exception e) {
            log.error("Error stopping recording", e);
            setState(AgentState.ONLINE);
        }
    }

    /**
     * Called when the heartbeat scheduler detects connection loss.
     */
    public void onConnectionLost() {
        if (state == AgentState.RECORDING) {
            // Recording continues even without server connection (offline buffering)
            log.warn("Connection lost during recording, segments will be buffered locally");
        } else if (state == AgentState.ONLINE) {
            setState(AgentState.DISCONNECTED);
        }
    }

    /**
     * Called when the heartbeat scheduler re-establishes connection.
     */
    public void onConnectionRestored() {
        if (state == AgentState.DISCONNECTED) {
            setState(AgentState.ONLINE);
            // Retry pending segments
            uploadQueue.retryPendingSegments();
        }
    }

    /**
     * Shuts down the agent gracefully.
     */
    public void shutdown() {
        log.info("Shutting down agent service...");

        // Stop recording if active
        if (state == AgentState.RECORDING) {
            stopRecording();
        }

        // Stop heartbeat
        heartbeatScheduler.stop();

        // Stop upload queue
        uploadQueue.stop();

        log.info("Agent service shut down");
    }

    // ---- State Management ----

    /**
     * Sets the agent state and notifies all listeners.
     */
    public void setState(AgentState newState) {
        AgentState oldState = this.state;
        if (oldState == newState) return;

        this.state = newState;
        log.info("State changed: {} -> {}", oldState, newState);

        for (StateChangeListener listener : stateListeners) {
            try {
                listener.onStateChanged(newState);
            } catch (Exception e) {
                log.error("Error notifying state listener", e);
            }
        }
    }

    /**
     * Registers a state change listener.
     */
    public void addStateListener(StateChangeListener listener) {
        stateListeners.add(listener);
    }

    // ---- Getters for UI and Heartbeat ----

    public String getDeviceId() {
        return authManager.getDeviceId();
    }

    public SessionManager.SessionStats getSessionStats() {
        return sessionManager.getStats();
    }

    public Instant getLastHeartbeatTime() {
        return heartbeatScheduler.getLastHeartbeatTime();
    }

    public Instant getLastUploadTime() {
        return sessionManager.getLastUploadTime();
    }

    public String getServerUrl() {
        return config.getServerBaseUrl();
    }

    public String getTenantName() {
        return authManager.getTenantName();
    }

    public String getDeviceHostname() {
        return HardwareId.getHostname();
    }

    public String getLogDir() {
        return config.getLogDir();
    }

    public int getUploadQueueSize() {
        return uploadQueue.getQueueSize();
    }

    public long getRecordingDurationSec() {
        SessionManager.SessionStats stats = sessionManager.getStats();
        if (stats != null && stats.getDuration() != null) {
            return stats.getDuration().getSeconds();
        }
        return 0;
    }

    public int getSegmentsSent() {
        SessionManager.SessionStats stats = sessionManager.getStats();
        return stats != null ? stats.getSegmentsSent() : 0;
    }

    public long getBytesSent() {
        SessionManager.SessionStats stats = sessionManager.getStats();
        return stats != null ? stats.getBytesSent() : 0;
    }
}
