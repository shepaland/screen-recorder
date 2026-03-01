package com.prg.agent.storage;

import com.prg.agent.config.AgentConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;

/**
 * Manages local segment files: directory structure, cleanup, and disk space monitoring.
 *
 * <p>Segments are stored under {segments.dir}/{session_id}/ with filenames
 * matching the pattern {session_id}_{sequence_num}.mp4.
 */
public class SegmentFileManager {

    private static final Logger log = LoggerFactory.getLogger(SegmentFileManager.class);

    private final AgentConfig config;

    public SegmentFileManager(AgentConfig config) {
        this.config = config;
    }

    /**
     * Returns the segments directory for a given session, creating it if necessary.
     */
    public File getSessionDir(String sessionId) {
        File dir = new File(config.getSegmentsDir(), sessionId);
        if (!dir.exists() && !dir.mkdirs()) {
            log.error("Failed to create session directory: {}", dir);
        }
        return dir;
    }

    /**
     * Returns the output pattern for FFmpeg segment files.
     * Example: /home/user/.prg-agent/segments/{sessionId}/{sessionId}_%05d.mp4
     */
    public String getSegmentOutputPattern(String sessionId) {
        File sessionDir = getSessionDir(sessionId);
        return new File(sessionDir, sessionId + "_%05d.mp4").getAbsolutePath();
    }

    /**
     * Computes SHA-256 checksum for a file.
     */
    public String computeChecksum(File file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            log.error("Failed to compute checksum for {}", file, e);
            return "";
        }
    }

    /**
     * Deletes a segment file after successful upload.
     */
    public boolean deleteSegmentFile(File file) {
        if (file.exists()) {
            boolean deleted = file.delete();
            if (deleted) {
                log.debug("Deleted segment file: {}", file.getName());
            } else {
                log.warn("Failed to delete segment file: {}", file);
            }
            return deleted;
        }
        return true;
    }

    /**
     * Cleans up empty session directories.
     */
    public void cleanupSessionDir(String sessionId) {
        File sessionDir = new File(config.getSegmentsDir(), sessionId);
        if (sessionDir.exists() && sessionDir.isDirectory()) {
            String[] files = sessionDir.list();
            if (files != null && files.length == 0) {
                if (sessionDir.delete()) {
                    log.debug("Cleaned up empty session directory: {}", sessionDir);
                }
            }
        }
    }

    /**
     * Returns the total size of all segment files in bytes.
     */
    public long getTotalSegmentSizeBytes() {
        File segmentsDir = new File(config.getSegmentsDir());
        if (!segmentsDir.exists()) {
            return 0;
        }
        return calculateDirSize(segmentsDir);
    }

    /**
     * Returns the total size in megabytes.
     */
    public double getTotalSegmentSizeMb() {
        return getTotalSegmentSizeBytes() / (1024.0 * 1024.0);
    }

    /**
     * Checks if the buffer has exceeded the maximum size limit.
     */
    public boolean isBufferFull() {
        long maxBytes = (long) config.getMaxBufferMb() * 1024 * 1024;
        return getTotalSegmentSizeBytes() >= maxBytes;
    }

    /**
     * Removes the oldest segment files to free up space when the buffer is full.
     * Removes files until the buffer is under 90% of the max limit.
     */
    public void evictOldestSegments() {
        long maxBytes = (long) config.getMaxBufferMb() * 1024 * 1024;
        long targetBytes = (long) (maxBytes * 0.9);
        long currentBytes = getTotalSegmentSizeBytes();

        if (currentBytes <= targetBytes) {
            return;
        }

        log.warn("Buffer is full ({} MB / {} MB), evicting oldest segments",
                currentBytes / (1024 * 1024), config.getMaxBufferMb());

        File segmentsDir = new File(config.getSegmentsDir());
        File[] sessionDirs = segmentsDir.listFiles(File::isDirectory);
        if (sessionDirs == null) return;

        // Get all segment files sorted by last modified time (oldest first)
        File[] allFiles = Arrays.stream(sessionDirs)
                .flatMap(dir -> {
                    File[] files = dir.listFiles((d, name) -> name.endsWith(".mp4"));
                    return files != null ? Arrays.stream(files) : Arrays.<File>stream(new File[0]);
                })
                .sorted(Comparator.comparingLong(File::lastModified))
                .toArray(File[]::new);

        for (File file : allFiles) {
            if (currentBytes <= targetBytes) break;
            long size = file.length();
            if (file.delete()) {
                currentBytes -= size;
                log.debug("Evicted old segment: {} ({} bytes)", file.getName(), size);
            }
        }

        // Clean up empty directories
        for (File dir : sessionDirs) {
            String[] remaining = dir.list();
            if (remaining != null && remaining.length == 0) {
                dir.delete();
            }
        }
    }

    /**
     * Returns the available disk space in GB.
     */
    public double getFreeDiskSpaceGb() {
        File dataDir = new File(config.getDataDir());
        return dataDir.getUsableSpace() / (1024.0 * 1024.0 * 1024.0);
    }

    private long calculateDirSize(File dir) {
        long size = 0;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    size += file.length();
                } else if (file.isDirectory()) {
                    size += calculateDirSize(file);
                }
            }
        }
        return size;
    }
}
