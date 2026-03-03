package com.prg.agent.capture;

import com.prg.agent.storage.SegmentFileManager;
import com.prg.agent.upload.UploadQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bridges the capture layer with the upload layer.
 *
 * <p>When a new segment file is produced by FFmpeg or the native capture pipeline,
 * SegmentProducer computes its checksum, extracts metadata, and enqueues it
 * into the UploadQueue for upload to the server.
 */
public class SegmentProducer {

    private static final Logger log = LoggerFactory.getLogger(SegmentProducer.class);

    private final SegmentFileManager segmentFileManager;
    private final UploadQueue uploadQueue;
    private final AtomicInteger sequenceCounter = new AtomicInteger(0);
    private volatile String currentSessionId;
    private volatile String currentDeviceId;

    public SegmentProducer(SegmentFileManager segmentFileManager, UploadQueue uploadQueue) {
        this.segmentFileManager = segmentFileManager;
        this.uploadQueue = uploadQueue;
    }

    /**
     * Sets the current session ID and device ID, and resets the sequence counter.
     */
    public void startSession(String sessionId, String deviceId) {
        this.currentSessionId = sessionId;
        this.currentDeviceId = deviceId;
        this.sequenceCounter.set(0);
        log.info("SegmentProducer started for session {} (device={})", sessionId, deviceId);
    }

    /**
     * Stops producing segments for the current session.
     */
    public void stopSession() {
        log.info("SegmentProducer stopped for session {} (produced {} segments)",
                currentSessionId, sequenceCounter.get());
        this.currentSessionId = null;
        this.currentDeviceId = null;
    }

    /**
     * Called when a new segment file has been completed by the capture layer.
     * Computes checksum and enqueues the segment for upload.
     */
    public void onNewSegment(File segmentFile) {
        String sessionId = currentSessionId;
        if (sessionId == null) {
            log.warn("Received segment but no active session, ignoring: {}", segmentFile.getName());
            return;
        }

        int seqNum = sequenceCounter.incrementAndGet();
        long sizeBytes = segmentFile.length();
        String checksum = segmentFileManager.computeChecksum(segmentFile);

        log.info("New segment #{}: {} ({} bytes, checksum={}...)",
                seqNum, segmentFile.getName(), sizeBytes,
                checksum.length() > 8 ? checksum.substring(0, 8) : checksum);

        UploadQueue.SegmentTask task = new UploadQueue.SegmentTask(
                segmentFile, sessionId, currentDeviceId, seqNum, sizeBytes, checksum);

        uploadQueue.enqueue(task);
    }

    /**
     * Returns the number of segments produced in the current session.
     */
    public int getSegmentCount() {
        return sequenceCounter.get();
    }
}
