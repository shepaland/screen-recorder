package com.prg.agent;

import com.formdev.flatlaf.FlatLightLaf;
import com.prg.agent.service.AgentService;
import com.prg.agent.ui.TrayManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.util.Arrays;

/**
 * Entry point for the PRG Windows Screen Recorder Agent.
 *
 * <p>Supports two modes:
 * <ul>
 *   <li>{@code --service} - headless mode for Windows Service execution</li>
 *   <li>no arguments - interactive mode with System Tray icon and config window</li>
 * </ul>
 */
public class AgentApplication {

    private static final Logger log = LoggerFactory.getLogger(AgentApplication.class);
    private static final String VERSION = "1.0.0";

    public static void main(String[] args) {
        log.info("PRG Screen Recorder Agent v{} starting...", VERSION);
        log.info("Java version: {}, OS: {} {}",
                System.getProperty("java.version"),
                System.getProperty("os.name"),
                System.getProperty("os.version"));

        boolean serviceMode = Arrays.asList(args).contains("--service");

        if (serviceMode) {
            log.info("Starting in SERVICE mode (headless)");
            startServiceMode();
        } else {
            log.info("Starting in INTERACTIVE mode (tray)");
            startInteractiveMode();
        }
    }

    private static void startServiceMode() {
        AgentService agentService = new AgentService();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown hook triggered, stopping agent...");
            agentService.shutdown();
        }, "shutdown-hook"));

        agentService.initialize();

        // Keep the main thread alive
        Object lock = new Object();
        synchronized (lock) {
            try {
                lock.wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("Main thread interrupted, shutting down");
            }
        }
    }

    private static void startInteractiveMode() {
        // Install FlatLaf look-and-feel on the EDT
        SwingUtilities.invokeLater(() -> {
            try {
                FlatLightLaf.setup();
                UIManager.put("Button.arc", 8);
                UIManager.put("TextComponent.arc", 8);
                UIManager.put("Component.arc", 8);
            } catch (Exception e) {
                log.warn("Failed to set FlatLaf look-and-feel, using default", e);
            }
        });

        AgentService agentService = new AgentService();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown hook triggered, stopping agent...");
            agentService.shutdown();
        }, "shutdown-hook"));

        // Initialize the tray on EDT
        SwingUtilities.invokeLater(() -> {
            TrayManager trayManager = new TrayManager(agentService);
            trayManager.initialize();
            agentService.setTrayManager(trayManager);

            // Start agent service in background thread
            new Thread(() -> {
                agentService.initialize();
            }, "agent-init").start();
        });
    }

    public static String getVersion() {
        return VERSION;
    }
}
