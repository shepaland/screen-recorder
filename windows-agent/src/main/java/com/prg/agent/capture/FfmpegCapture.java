package com.prg.agent.capture;

import com.prg.agent.config.AgentConfig;
import com.prg.agent.storage.SegmentFileManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * FFmpeg-based screen capture implementation (fallback for when native DLL is not available).
 *
 * <p>Uses gdigrab on Windows for desktop capture, avfoundation on macOS for development,
 * and x11grab on Linux. Produces fMP4 segments with H.264 encoding.
 *
 * <p>FFmpeg command:
 * <pre>
 * ffmpeg -f gdigrab -framerate {fps} -i desktop
 *   -c:v libx264 -preset ultrafast -tune zerolatency
 *   -f segment -segment_time {segment_duration}
 *   -segment_format mp4 -reset_timestamps 1
 *   -movflags +frag_keyframe+empty_moov+default_base_moof
 *   {output_dir}/{session_id}_%05d.mp4
 * </pre>
 */
public class FfmpegCapture {

    private static final Logger log = LoggerFactory.getLogger(FfmpegCapture.class);

    private final AgentConfig config;
    private final SegmentFileManager segmentFileManager;
    private volatile Process ffmpegProcess;
    private volatile Thread stderrReaderThread;
    private volatile Thread segmentWatcherThread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Consumer<File> onNewSegment;

    public FfmpegCapture(AgentConfig config, SegmentFileManager segmentFileManager) {
        this.config = config;
        this.segmentFileManager = segmentFileManager;
    }

    /**
     * Sets the callback invoked when a new segment file is completed.
     */
    public void setOnNewSegment(Consumer<File> onNewSegment) {
        this.onNewSegment = onNewSegment;
    }

    /**
     * Starts FFmpeg screen capture producing segmented fMP4 output.
     *
     * @param sessionId the recording session ID
     * @throws IOException if FFmpeg process cannot be started
     */
    public void start(String sessionId) throws IOException {
        if (running.get()) {
            log.warn("FFmpeg capture is already running");
            return;
        }

        String outputPattern = segmentFileManager.getSegmentOutputPattern(sessionId);
        File sessionDir = segmentFileManager.getSessionDir(sessionId);

        List<String> command = buildFfmpegCommand(outputPattern);
        log.info("Starting FFmpeg capture: {}", String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);
        pb.directory(sessionDir);

        ffmpegProcess = pb.start();
        running.set(true);

        // Start stderr reader thread to capture FFmpeg logs
        stderrReaderThread = new Thread(() -> readFfmpegStderr(ffmpegProcess), "ffmpeg-stderr");
        stderrReaderThread.setDaemon(true);
        stderrReaderThread.start();

        // Close stdin so FFmpeg doesn't wait for input
        ffmpegProcess.getOutputStream().close();

        // Start segment watcher thread
        segmentWatcherThread = new Thread(() -> watchForNewSegments(sessionDir, sessionId), "segment-watcher");
        segmentWatcherThread.setDaemon(true);
        segmentWatcherThread.start();

        log.info("FFmpeg capture started for session {}", sessionId);
    }

    /**
     * Stops the FFmpeg capture process gracefully.
     */
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            log.debug("FFmpeg capture is not running");
            return;
        }

        log.info("Stopping FFmpeg capture...");

        Process process = ffmpegProcess;
        if (process != null && process.isAlive()) {
            // Send 'q' to FFmpeg stdin for graceful shutdown (if stdin is still open)
            try {
                OutputStream stdin = process.getOutputStream();
                stdin.write('q');
                stdin.flush();
            } catch (IOException e) {
                // stdin might already be closed
                log.debug("Could not send 'q' to FFmpeg, will destroy process");
            }

            // Wait for graceful shutdown
            try {
                boolean exited = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                if (!exited) {
                    log.warn("FFmpeg did not exit gracefully, destroying process");
                    process.destroyForcibly();
                    process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }

        // Interrupt watcher thread
        if (segmentWatcherThread != null) {
            segmentWatcherThread.interrupt();
        }

        ffmpegProcess = null;
        log.info("FFmpeg capture stopped");
    }

    /**
     * Returns true if the FFmpeg process is currently running.
     */
    public boolean isRunning() {
        return running.get() && ffmpegProcess != null && ffmpegProcess.isAlive();
    }

    /**
     * Builds the FFmpeg command line based on configuration and platform.
     */
    private List<String> buildFfmpegCommand(String outputPattern) {
        List<String> cmd = new ArrayList<>();
        cmd.add(config.getResolvedFfmpegPath());

        // Input format based on OS
        String osName = System.getProperty("os.name", "").toLowerCase();
        if (osName.contains("windows")) {
            cmd.addAll(List.of("-f", "gdigrab", "-framerate", String.valueOf(config.getCaptureFps()), "-i", "desktop"));
        } else if (osName.contains("mac")) {
            // macOS: use avfoundation (screen 0)
            cmd.addAll(List.of("-f", "avfoundation", "-framerate", String.valueOf(config.getCaptureFps()),
                    "-capture_cursor", "1", "-i", "0:none"));
        } else {
            // Linux: x11grab
            cmd.addAll(List.of("-f", "x11grab", "-framerate", String.valueOf(config.getCaptureFps()),
                    "-video_size", "1920x1080", "-i", ":0.0"));
        }

        // Encoder settings
        String preset = mapQualityToPreset(config.getCaptureQuality());
        cmd.addAll(List.of(
                "-c:v", "libx264",
                "-preset", preset,
                "-tune", "zerolatency",
                "-pix_fmt", "yuv420p"
        ));

        // Bitrate based on quality
        String bitrate = mapQualityToBitrate(config.getCaptureQuality());
        cmd.addAll(List.of("-b:v", bitrate));

        // Keyframe interval: every 2 seconds
        int gopSize = config.getCaptureFps() * 2;
        cmd.addAll(List.of("-g", String.valueOf(gopSize)));

        // Segmented output
        cmd.addAll(List.of(
                "-f", "segment",
                "-segment_time", String.valueOf(config.getSegmentDurationSec()),
                "-segment_format", "mp4",
                "-reset_timestamps", "1",
                "-movflags", "+frag_keyframe+empty_moov+default_base_moof"
        ));

        // Overwrite without asking
        cmd.add("-y");

        // Output pattern
        cmd.add(outputPattern);

        return cmd;
    }

    private String mapQualityToPreset(String quality) {
        return switch (quality != null ? quality.toLowerCase() : "medium") {
            case "low" -> "ultrafast";
            case "medium" -> "ultrafast";
            case "high" -> "fast";
            default -> "ultrafast";
        };
    }

    private String mapQualityToBitrate(String quality) {
        return switch (quality != null ? quality.toLowerCase() : "medium") {
            case "low" -> "300k";
            case "medium" -> "800k";
            case "high" -> "1500k";
            default -> "800k";
        };
    }

    /**
     * Reads FFmpeg stderr output and logs it.
     */
    private void readFfmpegStderr(Process process) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getErrorStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("Error") || line.contains("error")) {
                    log.error("FFmpeg: {}", line);
                } else if (line.contains("Warning") || line.contains("warning")) {
                    log.warn("FFmpeg: {}", line);
                } else {
                    log.debug("FFmpeg: {}", line);
                }
            }
        } catch (IOException e) {
            if (running.get()) {
                log.error("Error reading FFmpeg stderr", e);
            }
        }
    }

    /**
     * Watches the session directory for new segment files.
     * Uses polling with a short interval to detect new segments.
     */
    private void watchForNewSegments(File sessionDir, String sessionId) {
        log.debug("Starting segment watcher for directory: {}", sessionDir);

        // Track known files to detect new ones
        java.util.Set<String> knownFiles = new java.util.concurrent.ConcurrentSkipListSet<>();
        File lastReportedFile = null;

        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(1000); // Poll every 1 second

                File[] files = sessionDir.listFiles((dir, name) ->
                        name.startsWith(sessionId + "_") && name.endsWith(".mp4"));

                if (files == null || files.length == 0) {
                    continue;
                }

                // Sort by name (which includes sequence number)
                java.util.Arrays.sort(files, java.util.Comparator.comparing(File::getName));

                // The last file is currently being written, so skip it.
                // Notify about all completed files (all except the last one).
                for (int i = 0; i < files.length - 1; i++) {
                    File file = files[i];
                    if (!knownFiles.contains(file.getName()) && file.length() > 0) {
                        knownFiles.add(file.getName());
                        log.info("New segment completed: {} ({} bytes)", file.getName(), file.length());
                        if (onNewSegment != null) {
                            try {
                                onNewSegment.accept(file);
                            } catch (Exception e) {
                                log.error("Error in new segment callback for {}", file.getName(), e);
                            }
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error in segment watcher", e);
            }
        }

        // After stopping, report the final segment
        File[] finalFiles = sessionDir.listFiles((dir, name) ->
                name.startsWith(sessionId + "_") && name.endsWith(".mp4"));
        if (finalFiles != null) {
            for (File file : finalFiles) {
                if (!knownFiles.contains(file.getName()) && file.length() > 0) {
                    knownFiles.add(file.getName());
                    log.info("Final segment: {} ({} bytes)", file.getName(), file.length());
                    if (onNewSegment != null) {
                        try {
                            onNewSegment.accept(file);
                        } catch (Exception e) {
                            log.error("Error in final segment callback for {}", file.getName(), e);
                        }
                    }
                }
            }
        }

        log.debug("Segment watcher stopped");
    }
}
