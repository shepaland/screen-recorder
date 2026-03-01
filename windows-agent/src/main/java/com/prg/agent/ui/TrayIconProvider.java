package com.prg.agent.ui;

import com.prg.agent.AgentState;

import java.awt.*;
import java.awt.image.BufferedImage;

/**
 * Generates colored tray icons programmatically based on agent state.
 *
 * <p>Each state maps to a distinct color:
 * <ul>
 *   <li>NOT_AUTHENTICATED - Red</li>
 *   <li>DISCONNECTED - Orange</li>
 *   <li>ONLINE - Yellow</li>
 *   <li>RECORDING - Green</li>
 * </ul>
 *
 * <p>Icons are 16x16 circles with a darker border and a subtle highlight.
 */
public class TrayIconProvider {

    private TrayIconProvider() {
    }

    /**
     * Creates a 16x16 colored circle icon for the given agent state.
     */
    public static Image createIcon(AgentState state) {
        int size = 16;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        Color color = getColorForState(state);

        // Outer circle (dark border)
        g.setColor(color.darker());
        g.fillOval(1, 1, size - 2, size - 2);

        // Inner circle (bright fill)
        g.setColor(color);
        g.fillOval(2, 2, size - 4, size - 4);

        // Highlight (top-left specular reflection)
        g.setColor(new Color(255, 255, 255, 80));
        g.fillOval(4, 3, size / 2 - 2, size / 2 - 2);

        g.dispose();
        return image;
    }

    /**
     * Returns the color associated with the given agent state.
     */
    public static Color getColorForState(AgentState state) {
        return switch (state) {
            case NOT_AUTHENTICATED -> new Color(220, 50, 50);   // Red
            case DISCONNECTED -> new Color(255, 165, 0);        // Orange
            case ONLINE -> new Color(255, 220, 50);             // Yellow
            case RECORDING -> new Color(50, 200, 50);           // Green
        };
    }
}
