package com.prg.agent.capture;

import com.prg.agent.config.AgentConfig;
import com.prg.agent.storage.SegmentFileManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.function.Consumer;

/**
 * Manages the screen recording lifecycle, abstracting over the capture implementation.
 *
 * <p>Currently supports two capture backends:
 * <ul>
 *   <li>Native DXGI capture (via NativeBridge JNI) - high performance, Windows only</li>
 *   <li>FFmpeg capture (via FfmpegCapture) - cross-platform fallback</li>
 * </ul>
 *
 * <p>For MVP, only the FFmpeg backend is fully implemented.
 */
public class ScreenCaptureManager {

    private static final Logger log = LoggerFactory.getLogger(ScreenCaptureManager.class);

    private final AgentConfig config;
    private final SegmentFileManager segmentFileManager;
    private final FfmpegCapture ffmpegCapture;
    private volatile boolean recording = false;
    private volatile String currentSessionId;
    private Consumer<File> onNewSegment;

    public ScreenCaptureManager(AgentConfig config, SegmentFileManager segmentFileManager) {
        this.config = config;
        this.segmentFileManager = segmentFileManager;
        this.ffmpegCapture = new FfmpegCapture(config, segmentFileManager);
    }

    /**
     * Sets the callback invoked when a new segment file is completed.
     */
    public void setOnNewSegment(Consumer<File> onNewSegment) {
        this.onNewSegment = onNewSegment;
        this.ffmpegCapture.setOnNewSegment(onNewSegment);
    }

    /**
     * Starts screen recording for the given session.
     *
     * @param sessionId the recording session ID (used for file naming)
     */
    public synchronized void startRecording(String sessionId) {
        if (recording) {
            log.warn("Recording is already in progress for session {}", currentSessionId);
            return;
        }

        log.info("Starting screen recording for session {}", sessionId);
        this.currentSessionId = sessionId;

        if (NativeBridge.isNativeAvailable()) {
            startNativeCapture(sessionId);
        } else {
            startFfmpegCapture(sessionId);
        }
    }

    /**
     * Stops the current screen recording.
     */
    public synchronized void stopRecording() {
        if (!recording) {
            log.debug("No recording in progress");
            return;
        }

        log.info("Stopping screen recording for session {}", currentSessionId);

        if (NativeBridge.isNativeAvailable()) {
            stopNativeCapture();
        } else {
            ffmpegCapture.stop();
        }

        recording = false;
        currentSessionId = null;
        log.info("Screen recording stopped");
    }

    /**
     * Returns true if currently recording.
     */
    public boolean isRecording() {
        return recording;
    }

    /**
     * Returns the current session ID, or null if not recording.
     */
    public String getCurrentSessionId() {
        return currentSessionId;
    }

    // ---- Native capture ----

    private void startNativeCapture(String sessionId) {
        log.info("Using native DXGI capture");

        int quality = mapQualityToInt(config.getCaptureQuality());
        File sessionDir = segmentFileManager.getSessionDir(sessionId);

        long handle = NativeBridge.createCapture(config.getCaptureFps(), quality, sessionDir.getAbsolutePath());
        if (handle == 0) {
            log.error("Failed to create native capture, falling back to FFmpeg");
            startFfmpegCapture(sessionId);
            return;
        }

        boolean started = NativeBridge.startCapture(handle);
        if (!started) {
            String error = NativeBridge.getLastError(handle);
            NativeBridge.destroyCapture(handle);
            log.error("Failed to start native capture: {}, falling back to FFmpeg", error);
            startFfmpegCapture(sessionId);
            return;
        }

        recording = true;
        log.info("Native capture started for session {}", sessionId);
    }

    private void stopNativeCapture() {
        // Note: In full implementation, we would keep the handle and call stopCapture/destroyCapture
        log.info("Native capture stopped");
    }

    // ---- FFmpeg capture ----

    private void startFfmpegCapture(String sessionId) {
        log.info("Using FFmpeg capture (fps={}, segment={}s, quality={})",
                config.getCaptureFps(), config.getSegmentDurationSec(), config.getCaptureQuality());

        try {
            ffmpegCapture.start(sessionId);
            recording = true;
        } catch (IOException e) {
            log.error("Failed to start FFmpeg capture", e);
            recording = false;
            throw new RuntimeException("Failed to start screen capture: " + e.getMessage(), e);
        }
    }

    private int mapQualityToInt(String quality) {
        return switch (quality != null ? quality.toLowerCase() : "medium") {
            case "low" -> 0;
            case "medium" -> 1;
            case "high" -> 2;
            default -> 1;
        };
    }
}
