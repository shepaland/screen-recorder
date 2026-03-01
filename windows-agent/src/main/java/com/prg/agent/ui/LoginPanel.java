package com.prg.agent.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

/**
 * Swing panel containing the device login form.
 *
 * <p>Displays three input fields:
 * <ul>
 *   <li>Organization registration token</li>
 *   <li>Username</li>
 *   <li>Password</li>
 * </ul>
 *
 * <p>On submit, invokes the provided LoginCallback in a background thread
 * to avoid blocking the EDT. Shows loading state and error messages.
 */
public class LoginPanel extends JPanel {

    private final JTextField tokenField;
    private final JTextField usernameField;
    private final JPasswordField passwordField;
    private final JButton loginButton;
    private final JTextArea errorArea;
    private final LoginCallback callback;

    /**
     * Callback interface for login attempts.
     */
    @FunctionalInterface
    public interface LoginCallback {
        void onLogin(String token, String username, String password);
    }

    public LoginPanel(LoginCallback callback) {
        this.callback = callback;
        setLayout(new BorderLayout());
        setBorder(new EmptyBorder(20, 30, 20, 30));

        // Title
        JLabel titleLabel = new JLabel("PRG Screen Recorder", SwingConstants.CENTER);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 18f));
        titleLabel.setBorder(new EmptyBorder(0, 0, 25, 0));
        add(titleLabel, BorderLayout.NORTH);

        // Form
        JPanel formPanel = new JPanel();
        formPanel.setLayout(new BoxLayout(formPanel, BoxLayout.Y_AXIS));

        // Token field
        JLabel tokenLabel = new JLabel("Токен организации:");
        tokenLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        tokenLabel.setFont(tokenLabel.getFont().deriveFont(12f));
        formPanel.add(tokenLabel);
        formPanel.add(Box.createRigidArea(new Dimension(0, 4)));

        tokenField = new JTextField(30);
        tokenField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        tokenField.setAlignmentX(Component.LEFT_ALIGNMENT);
        tokenField.putClientProperty("JTextField.placeholderText", "drt_...");
        formPanel.add(tokenField);
        formPanel.add(Box.createRigidArea(new Dimension(0, 15)));

        // Username field
        JLabel usernameLabel = new JLabel("Имя пользователя:");
        usernameLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        usernameLabel.setFont(usernameLabel.getFont().deriveFont(12f));
        formPanel.add(usernameLabel);
        formPanel.add(Box.createRigidArea(new Dimension(0, 4)));

        usernameField = new JTextField(30);
        usernameField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        usernameField.setAlignmentX(Component.LEFT_ALIGNMENT);
        formPanel.add(usernameField);
        formPanel.add(Box.createRigidArea(new Dimension(0, 15)));

        // Password field
        JLabel passwordLabel = new JLabel("Пароль:");
        passwordLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        passwordLabel.setFont(passwordLabel.getFont().deriveFont(12f));
        formPanel.add(passwordLabel);
        formPanel.add(Box.createRigidArea(new Dimension(0, 4)));

        passwordField = new JPasswordField(30);
        passwordField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        passwordField.setAlignmentX(Component.LEFT_ALIGNMENT);
        formPanel.add(passwordField);
        formPanel.add(Box.createRigidArea(new Dimension(0, 20)));

        // Login button
        loginButton = new JButton("Войти");
        loginButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        loginButton.setPreferredSize(new Dimension(200, 36));
        loginButton.setMaximumSize(new Dimension(200, 36));
        loginButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        loginButton.addActionListener(e -> onLoginClicked());

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        buttonPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        buttonPanel.add(loginButton);
        formPanel.add(buttonPanel);
        formPanel.add(Box.createRigidArea(new Dimension(0, 15)));

        // Error area
        errorArea = new JTextArea(3, 30);
        errorArea.setEditable(false);
        errorArea.setLineWrap(true);
        errorArea.setWrapStyleWord(true);
        errorArea.setForeground(new Color(200, 50, 50));
        errorArea.setBackground(getBackground());
        errorArea.setFont(errorArea.getFont().deriveFont(12f));
        errorArea.setBorder(new EmptyBorder(5, 5, 5, 5));
        errorArea.setAlignmentX(Component.LEFT_ALIGNMENT);
        errorArea.setVisible(false);
        formPanel.add(errorArea);

        add(formPanel, BorderLayout.CENTER);

        // Enter key triggers login
        passwordField.addActionListener(e -> onLoginClicked());

        // Version footer
        JLabel versionLabel = new JLabel("v1.0.0", SwingConstants.CENTER);
        versionLabel.setFont(versionLabel.getFont().deriveFont(10f));
        versionLabel.setForeground(Color.GRAY);
        versionLabel.setBorder(new EmptyBorder(10, 0, 0, 0));
        add(versionLabel, BorderLayout.SOUTH);
    }

    private void onLoginClicked() {
        String token = tokenField.getText().trim();
        String username = usernameField.getText().trim();
        String password = new String(passwordField.getPassword());

        if (token.isEmpty() || username.isEmpty() || password.isEmpty()) {
            showError("Заполните все поля");
            return;
        }

        setLoading(true);
        clearError();

        // Execute login in background thread to avoid blocking EDT
        new Thread(() -> {
            try {
                callback.onLogin(token, username, password);
            } catch (Exception e) {
                // Error handling is done by ConfigWindow
            }
        }, "login-worker").start();
    }

    /**
     * Shows an error message below the form.
     */
    public void showError(String message) {
        SwingUtilities.invokeLater(() -> {
            errorArea.setText(message);
            errorArea.setVisible(true);
            setLoading(false);
        });
    }

    /**
     * Clears the error message.
     */
    public void clearError() {
        SwingUtilities.invokeLater(() -> {
            errorArea.setText("");
            errorArea.setVisible(false);
        });
    }

    /**
     * Sets the loading state (disables/enables input and shows loading text on button).
     */
    public void setLoading(boolean loading) {
        SwingUtilities.invokeLater(() -> {
            tokenField.setEnabled(!loading);
            usernameField.setEnabled(!loading);
            passwordField.setEnabled(!loading);
            loginButton.setEnabled(!loading);
            loginButton.setText(loading ? "Вход..." : "Войти");
        });
    }

    /**
     * Clears all input fields.
     */
    public void clearFields() {
        SwingUtilities.invokeLater(() -> {
            tokenField.setText("");
            usernameField.setText("");
            passwordField.setText("");
            clearError();
        });
    }
}
