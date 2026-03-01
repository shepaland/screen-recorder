package com.prg.agent.command;

import com.prg.agent.config.AgentConfig;
import com.prg.agent.service.AgentService;
import com.prg.agent.util.HttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CommandHandlerTest {

    @Mock
    private HttpClient httpClient;

    @Mock
    private AgentService agentService;

    private AgentConfig config;
    private CommandHandler commandHandler;

    @BeforeEach
    void setUp() {
        config = new AgentConfig();
        config.setServerControlUrl("https://test-server.example.com/api/v1/devices");

        commandHandler = new CommandHandler(config, httpClient);
        commandHandler.setAgentService(agentService);
    }

    @Test
    void testHandleStartRecording_startsCapture() throws Exception {
        // Given
        CommandHandler.CommandDto cmd = new CommandHandler.CommandDto();
        cmd.setId("cmd-uuid-1");
        cmd.setCommandType("START_RECORDING");
        cmd.setPayload(Map.of());

        // When
        commandHandler.handleCommands(List.of(cmd));

        // Then
        verify(agentService).startRecording();
        verify(httpClient).authPut(
                contains("commands/cmd-uuid-1/ack"),
                argThat(arg -> {
                    if (arg instanceof CommandHandler.AckRequest ack) {
                        return "acknowledged".equals(ack.getStatus());
                    }
                    return false;
                }),
                eq(Void.class)
        );
    }

    @Test
    void testHandleStopRecording_stopsCapture() throws Exception {
        // Given
        CommandHandler.CommandDto cmd = new CommandHandler.CommandDto();
        cmd.setId("cmd-uuid-2");
        cmd.setCommandType("STOP_RECORDING");
        cmd.setPayload(Map.of());

        // When
        commandHandler.handleCommands(List.of(cmd));

        // Then
        verify(agentService).stopRecording();
        verify(httpClient).authPut(
                contains("commands/cmd-uuid-2/ack"),
                any(CommandHandler.AckRequest.class),
                eq(Void.class)
        );
    }

    @Test
    void testHandleUpdateSettings_updatesConfig() throws Exception {
        // Given
        CommandHandler.CommandDto cmd = new CommandHandler.CommandDto();
        cmd.setId("cmd-uuid-3");
        cmd.setCommandType("UPDATE_SETTINGS");
        cmd.setPayload(Map.of(
                "capture_fps", 10,
                "segment_duration_sec", 15,
                "quality", "high"
        ));

        // When
        commandHandler.handleCommands(List.of(cmd));

        // Then
        assertEquals(10, config.getCaptureFps());
        assertEquals(15, config.getSegmentDurationSec());
        assertEquals("high", config.getCaptureQuality());

        verify(httpClient).authPut(
                contains("commands/cmd-uuid-3/ack"),
                any(CommandHandler.AckRequest.class),
                eq(Void.class)
        );
    }

    @Test
    void testHandleMultipleCommands_processesAll() throws Exception {
        // Given
        CommandHandler.CommandDto cmd1 = new CommandHandler.CommandDto();
        cmd1.setId("cmd-1");
        cmd1.setCommandType("UPDATE_SETTINGS");
        cmd1.setPayload(Map.of("capture_fps", 8));

        CommandHandler.CommandDto cmd2 = new CommandHandler.CommandDto();
        cmd2.setId("cmd-2");
        cmd2.setCommandType("START_RECORDING");
        cmd2.setPayload(Map.of());

        // When
        commandHandler.handleCommands(List.of(cmd1, cmd2));

        // Then
        assertEquals(8, config.getCaptureFps());
        verify(agentService).startRecording();
        verify(httpClient, times(2)).authPut(anyString(), any(CommandHandler.AckRequest.class), eq(Void.class));
    }

    @Test
    void testHandleUnknownCommand_acknowledgeFailed() throws Exception {
        // Given
        CommandHandler.CommandDto cmd = new CommandHandler.CommandDto();
        cmd.setId("cmd-unknown");
        cmd.setCommandType("UNKNOWN_COMMAND");
        cmd.setPayload(Map.of());

        // When
        commandHandler.handleCommands(List.of(cmd));

        // Then
        verify(httpClient).authPut(
                contains("commands/cmd-unknown/ack"),
                argThat(arg -> {
                    if (arg instanceof CommandHandler.AckRequest ack) {
                        return "failed".equals(ack.getStatus());
                    }
                    return false;
                }),
                eq(Void.class)
        );
    }

    @Test
    void testHandleNullCommands_doesNothing() {
        // When
        commandHandler.handleCommands(null);
        commandHandler.handleCommands(List.of());

        // Then
        verifyNoInteractions(agentService);
        verifyNoInteractions(httpClient);
    }

    @Test
    void testHandleCommand_exceptionInExecution_acknowledgesFailed() throws Exception {
        // Given
        CommandHandler.CommandDto cmd = new CommandHandler.CommandDto();
        cmd.setId("cmd-error");
        cmd.setCommandType("START_RECORDING");
        cmd.setPayload(Map.of());

        doThrow(new RuntimeException("Capture failed")).when(agentService).startRecording();

        // When
        commandHandler.handleCommands(List.of(cmd));

        // Then
        verify(httpClient).authPut(
                contains("commands/cmd-error/ack"),
                argThat(arg -> {
                    if (arg instanceof CommandHandler.AckRequest ack) {
                        return "failed".equals(ack.getStatus());
                    }
                    return false;
                }),
                eq(Void.class)
        );
    }
}
