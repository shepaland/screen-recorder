package com.prg.agent.ui;

import com.prg.agent.service.AgentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;

/**
 * Main configuration window (JFrame) using CardLayout to switch between
 * the login form and the status display.
 *
 * <p>Before authentication: shows LoginPanel with registration token,
 * username, and password fields.
 *
 * <p>After authentication: shows StatusPanel with connection info,
 * recording statistics, and server communication status.
 */
public class ConfigWindow extends JFrame {

    private static final Logger log = LoggerFactory.getLogger(ConfigWindow.class);

    private final AgentService agentService;
    private final CardLayout cardLayout;
    private final JPanel contentPanel;
    private final LoginPanel loginPanel;
    private final StatusPanel statusPanel;

    public ConfigWindow(AgentService agentService) {
        super("PRG Screen Recorder");
        this.agentService = agentService;

        setSize(420, 480);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(HIDE_ON_CLOSE);
        setResizable(false);

        // Set window icon
        setIconImage(TrayIconProvider.createIcon(agentService.getState()));

        // CardLayout for switching between login and status
        cardLayout = new CardLayout();
        contentPanel = new JPanel(cardLayout);

        loginPanel = new LoginPanel(this::onLogin);
        statusPanel = new StatusPanel(agentService);

        contentPanel.add(loginPanel, "login");
        contentPanel.add(statusPanel, "status");

        add(contentPanel);
    }

    /**
     * Switches to the login panel view.
     */
    public void showLogin() {
        SwingUtilities.invokeLater(() -> {
            statusPanel.stopRefresh();
            cardLayout.show(contentPanel, "login");
            loginPanel.clearFields();
        });
    }

    /**
     * Switches to the status panel view and starts periodic refresh.
     */
    public void showStatus() {
        SwingUtilities.invokeLater(() -> {
            cardLayout.show(contentPanel, "status");
            statusPanel.startRefresh();
        });
    }

    /**
     * Shows a login error message.
     */
    public void showLoginError(String message) {
        loginPanel.showError(message);
    }

    /**
     * Sets the loading state on the login panel.
     */
    public void setLoginLoading(boolean loading) {
        loginPanel.setLoading(loading);
    }

    /**
     * Login callback - executed in a background thread by LoginPanel.
     */
    private void onLogin(String token, String username, String password) {
        try {
            agentService.login(token, username, password);
            // Success - switch to status panel
            SwingUtilities.invokeLater(() -> {
                loginPanel.setLoading(false);
                showStatus();
            });
        } catch (Exception e) {
            log.error("Login failed: {}", e.getMessage());
            SwingUtilities.invokeLater(() -> {
                loginPanel.showError(e.getMessage());
            });
        }
    }
}
