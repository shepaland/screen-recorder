package com.prg.agent.upload;

import com.prg.agent.config.AgentConfig;
import com.prg.agent.storage.LocalDatabase;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thread-safe upload queue with configurable worker pool and retry logic.
 *
 * <p>Segments are enqueued by the SegmentProducer and processed by a pool of
 * worker threads. Failed uploads are retried with exponential backoff.
 * After exhausting retries, segments are saved to the LocalDatabase
 * for later retry when connectivity is restored.
 */
public class UploadQueue {

    private static final Logger log = LoggerFactory.getLogger(UploadQueue.class);

    private final AgentConfig config;
    private final SegmentUploader segmentUploader;
    private final LocalDatabase localDatabase;
    private final LinkedBlockingQueue<SegmentTask> queue;
    private final ExecutorService workerPool;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread dispatcherThread;

    public UploadQueue(AgentConfig config, SegmentUploader segmentUploader, LocalDatabase localDatabase) {
        this.config = config;
        this.segmentUploader = segmentUploader;
        this.localDatabase = localDatabase;
        this.queue = new LinkedBlockingQueue<>(1000); // Max 1000 segments in queue
        this.workerPool = Executors.newFixedThreadPool(config.getUploadThreads(),
                r -> {
                    Thread t = new Thread(r, "upload-worker");
                    t.setDaemon(true);
                    return t;
                });
    }

    /**
     * Starts the upload queue dispatcher.
     */
    public void start() {
        if (running.compareAndSet(false, true)) {
            dispatcherThread = new Thread(this::dispatchLoop, "upload-dispatcher");
            dispatcherThread.setDaemon(true);
            dispatcherThread.start();
            log.info("Upload queue started with {} worker threads", config.getUploadThreads());
        }
    }

    /**
     * Stops the upload queue and waits for pending tasks to complete.
     */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("Stopping upload queue ({} items remaining)", queue.size());

            if (dispatcherThread != null) {
                dispatcherThread.interrupt();
            }

            workerPool.shutdown();
            try {
                if (!workerPool.awaitTermination(30, TimeUnit.SECONDS)) {
                    workerPool.shutdownNow();
                    log.warn("Upload queue shutdown timed out, forcing");
                }
            } catch (InterruptedException e) {
                workerPool.shutdownNow();
                Thread.currentThread().interrupt();
            }

            // Save remaining items to local database
            SegmentTask task;
            while ((task = queue.poll()) != null) {
                saveToLocalDatabase(task);
            }

            log.info("Upload queue stopped");
        }
    }

    /**
     * Enqueues a segment for upload.
     */
    public void enqueue(SegmentTask task) {
        if (!running.get()) {
            log.warn("Upload queue is not running, saving segment to local database");
            saveToLocalDatabase(task);
            return;
        }

        if (!queue.offer(task)) {
            log.warn("Upload queue is full, saving segment to local database");
            saveToLocalDatabase(task);
            return;
        }

        log.debug("Enqueued segment #{} for upload (queue size: {})", task.sequenceNum, queue.size());
    }

    /**
     * Returns the number of items currently in the queue.
     */
    public int getQueueSize() {
        return queue.size();
    }

    /**
     * Returns true if the queue is currently processing uploads.
     */
    public boolean isRunning() {
        return running.get();
    }

    private void dispatchLoop() {
        log.debug("Upload dispatcher started");

        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                SegmentTask task = queue.poll(1, TimeUnit.SECONDS);
                if (task != null) {
                    CompletableFuture.runAsync(() -> processTask(task), workerPool);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        log.debug("Upload dispatcher stopped");
    }

    private void processTask(SegmentTask task) {
        int maxRetries = config.getUploadRetryMax();

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                segmentUploader.uploadSegment(
                        task.file, task.sessionId, task.sequenceNum,
                        task.sizeBytes, task.checksum);
                log.debug("Segment #{} uploaded successfully", task.sequenceNum);
                return; // Success
            } catch (Exception e) {
                if (attempt < maxRetries) {
                    long delayMs = 1000L * (long) Math.pow(2, attempt); // 1s, 2s, 4s
                    log.warn("Upload failed for segment #{} (attempt {}/{}), retrying in {}ms: {}",
                            task.sequenceNum, attempt + 1, maxRetries + 1, delayMs, e.getMessage());
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        saveToLocalDatabase(task);
                        return;
                    }
                } else {
                    log.error("Upload failed for segment #{} after {} attempts, saving to local database",
                            task.sequenceNum, maxRetries + 1, e);
                    saveToLocalDatabase(task);
                }
            }
        }
    }

    private void saveToLocalDatabase(SegmentTask task) {
        try {
            localDatabase.addPendingSegment(
                    task.sessionId, task.sequenceNum, task.file.getAbsolutePath(),
                    task.sizeBytes, task.checksum);
            log.info("Segment #{} saved to local database for later upload", task.sequenceNum);
        } catch (Exception e) {
            log.error("Failed to save segment #{} to local database", task.sequenceNum, e);
        }
    }

    /**
     * Retries uploading segments that were previously saved to the local database.
     */
    public void retryPendingSegments() {
        var pending = localDatabase.getRetryableSegments(config.getUploadRetryMax());
        if (pending.isEmpty()) return;

        log.info("Retrying {} pending segments from local database", pending.size());

        for (var segment : pending) {
            File file = new File(segment.getFilePath());
            if (!file.exists()) {
                log.warn("Pending segment file not found, removing from database: {}", segment.getFilePath());
                localDatabase.removePendingSegment(segment.getId());
                continue;
            }

            try {
                segmentUploader.uploadSegment(
                        file, segment.getSessionId(), segment.getSequenceNum(),
                        segment.getSizeBytes(), segment.getChecksum());
                localDatabase.removePendingSegment(segment.getId());
                log.info("Pending segment #{} uploaded successfully", segment.getSequenceNum());
            } catch (Exception e) {
                localDatabase.incrementRetryCount(segment.getId());
                log.warn("Retry failed for pending segment #{}: {}", segment.getSequenceNum(), e.getMessage());
            }
        }
    }

    /**
     * A segment upload task.
     */
    @Data
    @AllArgsConstructor
    public static class SegmentTask {
        private final File file;
        private final String sessionId;
        private final int sequenceNum;
        private final long sizeBytes;
        private final String checksum;
    }
}
