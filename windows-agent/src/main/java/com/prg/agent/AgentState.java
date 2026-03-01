package com.prg.agent;

import lombok.Getter;

/**
 * Agent lifecycle states. Each state maps to a tray icon color and description.
 */
@Getter
public enum AgentState {

    NOT_AUTHENTICATED("Требуется вход", "tray_red.png"),
    DISCONNECTED("Нет подключения к серверу", "tray_orange.png"),
    ONLINE("Подключён (не записывает)", "tray_yellow.png"),
    RECORDING("Запись ведётся", "tray_green.png");

    private final String description;
    private final String iconName;

    AgentState(String description, String iconName) {
        this.description = description;
        this.iconName = iconName;
    }
}
