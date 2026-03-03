package com.prg.agent.command;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.prg.agent.config.AgentConfig;
import com.prg.agent.service.AgentService;
import com.prg.agent.service.MetricsCollector;
import com.prg.agent.util.HttpClient;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Periodically sends heartbeat to the control-plane server.
 *
 * <p>The heartbeat serves dual purposes:
 * <ul>
 *   <li>Reports agent status and system metrics to the server</li>
 *   <li>Receives pending commands from the server (piggyback)</li>
 * </ul>
 *
 * <p>The heartbeat interval is initially configured locally but can be
 * dynamically adjusted by the server via the response.
 */
public class HeartbeatScheduler {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatScheduler.class);

    private final AgentConfig config;
    private final HttpClient httpClient;
    private final MetricsCollector metricsCollector;
    private final CommandHandler commandHandler;
    private volatile AgentService agentService;

    private ScheduledExecutorService scheduler;
    private volatile int intervalSec = 30;
    private volatile ScheduledFuture<?> scheduledTask;

    @Getter
    private volatile Instant lastHeartbeatTime;
    @Getter
    private volatile boolean connected = false;
    private volatile int consecutiveFailures = 0;

    public HeartbeatScheduler(AgentConfig config, HttpClient httpClient,
                               MetricsCollector metricsCollector, CommandHandler commandHandler) {
        this.config = config;
        this.httpClient = httpClient;
        this.metricsCollector = metricsCollector;
        this.commandHandler = commandHandler;
    }

    /**
     * Sets the agent service reference.
     */
    public void setAgentService(AgentService agentService) {
        this.agentService = agentService;
    }

    /**
     * Starts the heartbeat scheduler.
     */
    public void start() {
        if (scheduler != null && !scheduler.isShutdown()) {
            log.debug("Heartbeat scheduler is already running");
            return;
        }

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "heartbeat");
            t.setDaemon(true);
            return t;
        });

        scheduleNextHeartbeat(0); // Send first heartbeat immediately
        log.info("Heartbeat scheduler started (interval: {} sec)", intervalSec);
    }

    /**
     * Stops the heartbeat scheduler.
     */
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            scheduler = null;
            connected = false;
            log.info("Heartbeat scheduler stopped");
        }
    }

    /**
     * Updates the heartbeat interval (can be called from server response).
     */
    public void setInterval(int seconds) {
        if (seconds > 0 && seconds != intervalSec) {
            this.intervalSec = seconds;
            log.info("Heartbeat interval updated to {} sec", seconds);
        }
    }

    private void scheduleNextHeartbeat(int delaySec) {
        if (scheduler == null || scheduler.isShutdown()) return;

        scheduledTask = scheduler.schedule(this::sendHeartbeat, delaySec, TimeUnit.SECONDS);
    }

    private void sendHeartbeat() {
        if (agentService == null) return;

        String deviceId = agentService.getDeviceId();
        if (deviceId == null) {
            log.debug("No device ID, skipping heartbeat");
            scheduleNextHeartbeat(intervalSec);
            return;
        }

        try {
            // Collect metrics
            MetricsCollector.SystemMetrics metrics = metricsCollector.collectMetrics();

            // Build heartbeat request
            HeartbeatRequest request = new HeartbeatRequest();
            request.setStatus(agentService.getState().name().toLowerCase());
            request.setAgentVersion("1.0.0");

            Map<String, Object> metricsMap = Map.of(
                    "cpu_percent", metrics.getCpuPercent(),
                    "memory_mb", metrics.getMemoryMb(),
                    "disk_free_gb", metrics.getDiskFreeGb(),
                    "segments_queued", agentService.getUploadQueueSize(),
                    "recording_duration_sec", agentService.getRecordingDurationSec(),
                    "segments_sent", agentService.getSegmentsSent(),
                    "bytes_sent", agentService.getBytesSent()
            );
            request.setMetrics(metricsMap);

            // Send heartbeat
            String url = config.getServerControlUrl() + "/" + deviceId + "/heartbeat";
            HeartbeatResponse response = httpClient.authPut(url, request, HeartbeatResponse.class);

            lastHeartbeatTime = Instant.now();
            boolean wasDisconnected = !connected;
            connected = true;
            consecutiveFailures = 0;

            // Notify agent about restored connection so buffered segments get retried
            if (wasDisconnected && agentService != null) {
                agentService.onConnectionRestored();
            }

            // Process pending commands
            if (response.getPendingCommands() != null && !response.getPendingCommands().isEmpty()) {
                commandHandler.handleCommands(response.getPendingCommands());
            }

            // Update interval from server
            if (response.getNextHeartbeatSec() > 0) {
                setInterval(response.getNextHeartbeatSec());
            }

            log.debug("Heartbeat sent successfully (next in {} sec)", intervalSec);

        } catch (Exception e) {
            consecutiveFailures++;
            connected = false;
            log.warn("Heartbeat failed (attempt #{}): {}", consecutiveFailures, e.getMessage());

            // Notify agent service about disconnection
            if (agentService != null && consecutiveFailures >= 3) {
                agentService.onConnectionLost();
            }
        }

        // Schedule next heartbeat
        scheduleNextHeartbeat(intervalSec);
    }

    // ---- DTOs ----

    @Data
    @NoArgsConstructor
    public static class HeartbeatRequest {
        private String status;
        @JsonProperty("agent_version")
        private String agentVersion;
        private Map<String, Object> metrics;
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class HeartbeatResponse {
        @JsonProperty("server_ts")
        private String serverTs;
        @JsonProperty("pending_commands")
        private List<CommandHandler.CommandDto> pendingCommands;
        @JsonProperty("next_heartbeat_sec")
        private int nextHeartbeatSec;
    }
}
