package com.prg.agent.command;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.prg.agent.config.AgentConfig;
import com.prg.agent.service.AgentService;
import com.prg.agent.util.HttpClient;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Processes commands received from the server via heartbeat responses.
 *
 * <p>Supported command types:
 * <ul>
 *   <li>START_RECORDING - Begin screen capture</li>
 *   <li>STOP_RECORDING - Stop screen capture</li>
 *   <li>UPDATE_SETTINGS - Update capture settings (fps, quality, etc.)</li>
 *   <li>RESTART_AGENT - Restart the agent process</li>
 *   <li>UNREGISTER - Remove device registration</li>
 * </ul>
 *
 * <p>Each processed command is acknowledged back to the server with its result status.
 */
public class CommandHandler {

    private static final Logger log = LoggerFactory.getLogger(CommandHandler.class);

    private final AgentConfig config;
    private final HttpClient httpClient;
    private volatile AgentService agentService;

    public CommandHandler(AgentConfig config, HttpClient httpClient) {
        this.config = config;
        this.httpClient = httpClient;
    }

    /**
     * Sets the agent service reference (set after construction to avoid circular dependency).
     */
    public void setAgentService(AgentService agentService) {
        this.agentService = agentService;
    }

    /**
     * Processes a list of commands received from the server.
     */
    public void handleCommands(List<CommandDto> commands) {
        if (commands == null || commands.isEmpty()) {
            return;
        }

        log.info("Processing {} server command(s)", commands.size());

        for (CommandDto cmd : commands) {
            try {
                log.info("Executing command: type={}, id={}", cmd.getCommandType(), cmd.getId());

                switch (cmd.getCommandType()) {
                    case "START_RECORDING" -> handleStartRecording(cmd);
                    case "STOP_RECORDING" -> handleStopRecording(cmd);
                    case "UPDATE_SETTINGS" -> handleUpdateSettings(cmd);
                    case "RESTART_AGENT" -> handleRestart(cmd);
                    case "UNREGISTER" -> handleUnregister(cmd);
                    default -> {
                        log.warn("Unknown command type: {}", cmd.getCommandType());
                        acknowledgeCommand(cmd.getId(), "failed",
                                Map.of("error", "Unknown command type: " + cmd.getCommandType()));
                        continue;
                    }
                }

                acknowledgeCommand(cmd.getId(), "acknowledged", Map.of("message", "OK"));

            } catch (Exception e) {
                log.error("Failed to execute command {}: {}", cmd.getId(), e.getMessage(), e);
                acknowledgeCommand(cmd.getId(), "failed",
                        Map.of("error", e.getMessage()));
            }
        }
    }

    private void handleStartRecording(CommandDto cmd) {
        log.info("Command: START_RECORDING");
        if (agentService != null) {
            agentService.startRecording();
        }
    }

    private void handleStopRecording(CommandDto cmd) {
        log.info("Command: STOP_RECORDING");
        if (agentService != null) {
            agentService.stopRecording();
        }
    }

    private void handleUpdateSettings(CommandDto cmd) {
        log.info("Command: UPDATE_SETTINGS, payload={}", cmd.getPayload());

        Map<String, Object> payload = cmd.getPayload();
        if (payload == null) return;

        if (payload.containsKey("capture_fps")) {
            int fps = ((Number) payload.get("capture_fps")).intValue();
            config.setCaptureFps(fps);
            log.info("Updated capture FPS to {}", fps);
        }

        if (payload.containsKey("segment_duration_sec")) {
            int duration = ((Number) payload.get("segment_duration_sec")).intValue();
            config.setSegmentDurationSec(duration);
            log.info("Updated segment duration to {} seconds", duration);
        }

        if (payload.containsKey("quality")) {
            String quality = String.valueOf(payload.get("quality"));
            config.setCaptureQuality(quality);
            log.info("Updated capture quality to {}", quality);
        }
    }

    private void handleRestart(CommandDto cmd) {
        log.info("Command: RESTART_AGENT - scheduling restart...");

        // Schedule restart in a separate thread to allow acknowledgment first
        new Thread(() -> {
            try {
                Thread.sleep(2000); // Wait for ack to be sent
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            if (agentService != null) {
                agentService.shutdown();
            }

            // Exit with code 42 to signal the service wrapper to restart
            System.exit(42);
        }, "agent-restart").start();
    }

    private void handleUnregister(CommandDto cmd) {
        log.info("Command: UNREGISTER - clearing credentials and stopping");

        if (agentService != null) {
            agentService.stopRecording();
        }

        // The agent service will handle credential cleanup on logout
        if (agentService != null) {
            agentService.logout();
        }
    }

    /**
     * Sends a command acknowledgment to the server.
     */
    private void acknowledgeCommand(String commandId, String status, Map<String, Object> result) {
        try {
            AckRequest ackRequest = new AckRequest();
            ackRequest.setStatus(status);
            ackRequest.setResult(result);

            String url = config.getServerControlUrl() + "/commands/" + commandId + "/ack";
            httpClient.authPut(url, ackRequest, Void.class);
            log.debug("Command {} acknowledged with status {}", commandId, status);
        } catch (Exception e) {
            log.error("Failed to acknowledge command {}: {}", commandId, e.getMessage());
        }
    }

    // ---- DTOs ----

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CommandDto {
        private String id;
        @JsonProperty("command_type")
        private String commandType;
        private Map<String, Object> payload;
        @JsonProperty("created_ts")
        private String createdTs;
    }

    @Data
    @NoArgsConstructor
    public static class AckRequest {
        private String status;
        private Map<String, Object> result;
    }
}
