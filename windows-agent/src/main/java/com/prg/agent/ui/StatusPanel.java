package com.prg.agent.ui;

import com.prg.agent.AgentState;
import com.prg.agent.service.AgentService;
import com.prg.agent.upload.SessionManager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.io.File;
import java.time.Duration;
import java.time.Instant;

/**
 * Swing panel displaying the agent status after successful authentication.
 *
 * <p>Shows three sections:
 * <ul>
 *   <li>Connection info: server URL, tenant, device hostname, status indicator</li>
 *   <li>Recording stats: duration, segments sent, data sent (updates every second)</li>
 *   <li>Server communication: last heartbeat and upload timestamps</li>
 * </ul>
 *
 * <p>Uses a Swing Timer to refresh the display every second.
 */
public class StatusPanel extends JPanel {

    private final AgentService agentService;

    // Connection section
    private final JLabel serverValueLabel;
    private final JLabel tenantValueLabel;
    private final JLabel deviceValueLabel;
    private final JLabel statusValueLabel;

    // Stats section
    private final JLabel durationValueLabel;
    private final JLabel chunksValueLabel;
    private final JLabel dataValueLabel;

    // Communication section
    private final JLabel heartbeatValueLabel;
    private final JLabel uploadValueLabel;

    private final Timer refreshTimer;

    public StatusPanel(AgentService agentService) {
        this.agentService = agentService;
        setLayout(new BorderLayout());
        setBorder(new EmptyBorder(15, 20, 15, 20));

        // Title
        JLabel titleLabel = new JLabel("PRG Screen Recorder", SwingConstants.CENTER);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 18f));
        titleLabel.setBorder(new EmptyBorder(0, 0, 15, 0));
        add(titleLabel, BorderLayout.NORTH);

        // Main content
        JPanel contentPanel = new JPanel();
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));

        // ---- Connection section ----
        JPanel connectionPanel = createSectionPanel("Подключение");

        serverValueLabel = new JLabel("-");
        tenantValueLabel = new JLabel("-");
        deviceValueLabel = new JLabel("-");
        statusValueLabel = new JLabel("-");

        addInfoRow(connectionPanel, "Сервер:", serverValueLabel);
        addInfoRow(connectionPanel, "Тенант:", tenantValueLabel);
        addInfoRow(connectionPanel, "Устройство:", deviceValueLabel);
        addInfoRow(connectionPanel, "Статус:", statusValueLabel);

        contentPanel.add(connectionPanel);
        contentPanel.add(Box.createRigidArea(new Dimension(0, 10)));

        // ---- Recording stats section ----
        JPanel statsPanel = createSectionPanel("Статистика записи");

        durationValueLabel = new JLabel("00:00:00");
        chunksValueLabel = new JLabel("0");
        dataValueLabel = new JLabel("0 B");

        addInfoRow(statsPanel, "Время записи:", durationValueLabel);
        addInfoRow(statsPanel, "Отправлено чанков:", chunksValueLabel);
        addInfoRow(statsPanel, "Отправлено данных:", dataValueLabel);

        contentPanel.add(statsPanel);
        contentPanel.add(Box.createRigidArea(new Dimension(0, 10)));

        // ---- Communication section ----
        JPanel commPanel = createSectionPanel("Связь с сервером");

        heartbeatValueLabel = new JLabel("-");
        uploadValueLabel = new JLabel("-");

        addInfoRow(commPanel, "Последний heartbeat:", heartbeatValueLabel);
        addInfoRow(commPanel, "Последняя отправка:", uploadValueLabel);

        contentPanel.add(commPanel);
        contentPanel.add(Box.createRigidArea(new Dimension(0, 15)));

        // ---- Log button ----
        JButton logButton = new JButton("Открыть файл логов");
        logButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        logButton.setMaximumSize(new Dimension(250, 36));
        logButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        logButton.addActionListener(e -> openLogFile());

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        buttonPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        buttonPanel.add(logButton);
        contentPanel.add(buttonPanel);

        add(contentPanel, BorderLayout.CENTER);

        // Version footer
        JLabel versionLabel = new JLabel("v1.0.0", SwingConstants.CENTER);
        versionLabel.setFont(versionLabel.getFont().deriveFont(10f));
        versionLabel.setForeground(Color.GRAY);
        versionLabel.setBorder(new EmptyBorder(10, 0, 0, 0));
        add(versionLabel, BorderLayout.SOUTH);

        // Refresh timer (every 1 second)
        refreshTimer = new Timer(1000, e -> refreshDisplay());
        refreshTimer.setInitialDelay(0);
    }

    /**
     * Starts the periodic UI refresh.
     */
    public void startRefresh() {
        refreshTimer.start();
    }

    /**
     * Stops the periodic UI refresh.
     */
    public void stopRefresh() {
        refreshTimer.stop();
    }

    /**
     * Updates all display fields with current data from the agent service.
     */
    private void refreshDisplay() {
        // Connection info
        String serverUrl = agentService.getServerUrl();
        serverValueLabel.setText(serverUrl != null ? serverUrl : "-");

        String tenantName = agentService.getTenantName();
        tenantValueLabel.setText(tenantName != null ? tenantName : "-");

        String hostname = agentService.getDeviceHostname();
        deviceValueLabel.setText(hostname != null ? hostname : "-");

        // Status with color
        AgentState state = agentService.getState();
        statusValueLabel.setText(state.getDescription());
        statusValueLabel.setForeground(TrayIconProvider.getColorForState(state).darker());

        // Recording stats
        SessionManager.SessionStats stats = agentService.getSessionStats();
        if (stats != null) {
            durationValueLabel.setText(stats.getFormattedDuration());
            chunksValueLabel.setText(String.valueOf(stats.getSegmentsSent()));
            dataValueLabel.setText(stats.getFormattedBytesSent());
        } else {
            durationValueLabel.setText("00:00:00");
            chunksValueLabel.setText("0");
            dataValueLabel.setText("0 B");
        }

        // Communication timestamps
        Instant lastHeartbeat = agentService.getLastHeartbeatTime();
        if (lastHeartbeat != null) {
            long secsAgo = Duration.between(lastHeartbeat, Instant.now()).getSeconds();
            heartbeatValueLabel.setText(secsAgo + " сек назад");
        } else {
            heartbeatValueLabel.setText("-");
        }

        Instant lastUpload = agentService.getLastUploadTime();
        if (lastUpload != null) {
            long secsAgo = Duration.between(lastUpload, Instant.now()).getSeconds();
            uploadValueLabel.setText(secsAgo + " сек назад");
        } else {
            uploadValueLabel.setText("-");
        }
    }

    private JPanel createSectionPanel(String title) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                title,
                TitledBorder.LEFT,
                TitledBorder.TOP
        ));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return panel;
    }

    private void addInfoRow(JPanel parent, String labelText, JLabel valueLabel) {
        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setBorder(new EmptyBorder(3, 10, 3, 10));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel label = new JLabel(labelText);
        label.setFont(label.getFont().deriveFont(12f));
        label.setForeground(new Color(100, 100, 100));
        label.setPreferredSize(new Dimension(150, 20));
        row.add(label, BorderLayout.WEST);

        valueLabel.setFont(valueLabel.getFont().deriveFont(12f));
        row.add(valueLabel, BorderLayout.CENTER);

        parent.add(row);
    }

    private void openLogFile() {
        try {
            String logDir = agentService.getLogDir();
            File logFile = new File(logDir, "agent.log");
            if (logFile.exists()) {
                Desktop.getDesktop().open(logFile);
            } else {
                JOptionPane.showMessageDialog(this,
                        "Файл логов не найден: " + logFile.getAbsolutePath(),
                        "Файл не найден",
                        JOptionPane.WARNING_MESSAGE);
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this,
                    "Не удалось открыть файл логов: " + e.getMessage(),
                    "Ошибка",
                    JOptionPane.ERROR_MESSAGE);
        }
    }
}
