package com.prg.agent.ui;

import com.prg.agent.AgentState;
import com.prg.agent.service.AgentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;

/**
 * Manages the System Tray icon and context menu.
 *
 * <p>The tray icon color reflects the current agent state (red/orange/yellow/green),
 * and the tooltip shows the current status description. The context menu provides
 * access to the config window, log file, and exit action.
 */
public class TrayManager {

    private static final Logger log = LoggerFactory.getLogger(TrayManager.class);

    private final AgentService agentService;
    private SystemTray tray;
    private TrayIcon trayIcon;
    private MenuItem statusItem;
    private ConfigWindow configWindow;

    public TrayManager(AgentService agentService) {
        this.agentService = agentService;
    }

    /**
     * Initializes the System Tray icon and registers the context menu.
     */
    public void initialize() {
        if (!SystemTray.isSupported()) {
            log.error("System tray is not supported on this platform");
            return;
        }

        tray = SystemTray.getSystemTray();

        // Create icon with initial state
        Image icon = TrayIconProvider.createIcon(AgentState.NOT_AUTHENTICATED);
        trayIcon = new TrayIcon(icon, "PRG Recorder: " + AgentState.NOT_AUTHENTICATED.getDescription());
        trayIcon.setImageAutoSize(true);

        // Context menu
        PopupMenu popup = createPopupMenu();
        trayIcon.setPopupMenu(popup);

        // Double-click opens config window
        trayIcon.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    showConfigWindow();
                }
            }
        });

        try {
            tray.add(trayIcon);
            log.info("System tray icon added");
        } catch (AWTException e) {
            log.error("Failed to add tray icon", e);
        }

        // Create config window
        configWindow = new ConfigWindow(agentService);

        // Register as state listener
        agentService.addStateListener(this::updateState);
    }

    /**
     * Updates the tray icon and tooltip to reflect the new agent state.
     */
    public void updateState(AgentState state) {
        if (trayIcon == null) return;

        java.awt.EventQueue.invokeLater(() -> {
            trayIcon.setImage(TrayIconProvider.createIcon(state));
            trayIcon.setToolTip("PRG Recorder: " + state.getDescription());

            if (statusItem != null) {
                statusItem.setLabel("Статус: " + state.getDescription());
            }

            // Update config window icon
            if (configWindow != null) {
                configWindow.setIconImage(TrayIconProvider.createIcon(state));
            }
        });
    }

    /**
     * Updates the recording time display in the tooltip.
     */
    public void updateRecordingTime(String formattedTime) {
        if (trayIcon == null) return;

        java.awt.EventQueue.invokeLater(() -> {
            if (agentService.getState() == AgentState.RECORDING) {
                trayIcon.setToolTip("PRG Recorder: Запись (" + formattedTime + ")");
            }
        });
    }

    /**
     * Shows the config window, creating it if necessary.
     */
    public void showConfigWindow() {
        if (configWindow == null) {
            configWindow = new ConfigWindow(agentService);
        }

        java.awt.EventQueue.invokeLater(() -> {
            // Show the appropriate panel based on auth state
            if (agentService.getState() == AgentState.NOT_AUTHENTICATED) {
                configWindow.showLogin();
            } else {
                configWindow.showStatus();
            }
            configWindow.setVisible(true);
            configWindow.toFront();
            configWindow.requestFocus();
        });
    }

    /**
     * Removes the tray icon from the system tray.
     */
    public void dispose() {
        if (tray != null && trayIcon != null) {
            tray.remove(trayIcon);
            log.info("System tray icon removed");
        }
        if (configWindow != null) {
            configWindow.dispose();
        }
    }

    /**
     * Returns the config window instance.
     */
    public ConfigWindow getConfigWindow() {
        return configWindow;
    }

    private PopupMenu createPopupMenu() {
        PopupMenu popup = new PopupMenu();

        // Status (disabled, informational)
        statusItem = new MenuItem("Статус: " + agentService.getState().getDescription());
        statusItem.setEnabled(false);
        popup.add(statusItem);

        popup.addSeparator();

        // Open config window
        MenuItem configItem = new MenuItem("Открыть конфигуратор...");
        configItem.addActionListener(e -> showConfigWindow());
        popup.add(configItem);

        // Open log file
        MenuItem logItem = new MenuItem("Открыть лог-файл");
        logItem.addActionListener(e -> openLogFile());
        popup.add(logItem);

        popup.addSeparator();

        // Version info (disabled)
        MenuItem versionItem = new MenuItem("PRG Recorder v1.0.0");
        versionItem.setEnabled(false);
        popup.add(versionItem);

        popup.addSeparator();

        // Exit
        MenuItem exitItem = new MenuItem("Выход");
        exitItem.addActionListener(e -> {
            log.info("Exit requested from tray menu");
            agentService.shutdown();
            dispose();
            System.exit(0);
        });
        popup.add(exitItem);

        return popup;
    }

    private void openLogFile() {
        try {
            String logDir = agentService.getLogDir();
            File logFile = new File(logDir, "agent.log");
            if (logFile.exists()) {
                Desktop.getDesktop().open(logFile);
            } else {
                log.warn("Log file not found: {}", logFile);
            }
        } catch (Exception e) {
            log.error("Failed to open log file", e);
        }
    }
}
