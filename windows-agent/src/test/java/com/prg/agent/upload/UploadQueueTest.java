package com.prg.agent.upload;

import com.prg.agent.config.AgentConfig;
import com.prg.agent.storage.LocalDatabase;
import com.prg.agent.util.HttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UploadQueueTest {

    @Mock
    private SegmentUploader segmentUploader;

    @Mock
    private LocalDatabase localDatabase;

    @TempDir
    Path tempDir;

    private AgentConfig config;
    private UploadQueue uploadQueue;

    @BeforeEach
    void setUp() {
        config = new AgentConfig();
        config.setUploadThreads(1);
        config.setUploadRetryMax(3);

        uploadQueue = new UploadQueue(config, segmentUploader, localDatabase);
    }

    @Test
    void testEnqueue_processesInOrder() throws Exception {
        // Given
        uploadQueue.start();

        File file1 = createTempFile("segment_1.mp4");
        File file2 = createTempFile("segment_2.mp4");

        CountDownLatch latch = new CountDownLatch(2);
        doAnswer(invocation -> {
            latch.countDown();
            return null;
        }).when(segmentUploader).uploadSegment(any(), anyString(), anyString(), anyInt(), anyLong(), anyString());

        // When
        uploadQueue.enqueue(new UploadQueue.SegmentTask(file1, "session-1", "device-1", 1, 1024, "checksum1"));
        uploadQueue.enqueue(new UploadQueue.SegmentTask(file2, "session-1", "device-1", 2, 2048, "checksum2"));

        // Then
        assertTrue(latch.await(10, TimeUnit.SECONDS), "Both segments should be processed");

        verify(segmentUploader).uploadSegment(eq(file1), eq("session-1"), eq("device-1"), eq(1), eq(1024L), eq("checksum1"));
        verify(segmentUploader).uploadSegment(eq(file2), eq("session-1"), eq("device-1"), eq(2), eq(2048L), eq("checksum2"));

        uploadQueue.stop();
    }

    @Test
    void testUploadFailure_retries() throws Exception {
        // Given
        uploadQueue.start();

        File file = createTempFile("segment_retry.mp4");
        CountDownLatch latch = new CountDownLatch(1);

        // Fail twice, then succeed
        doThrow(new HttpClient.HttpException(500, "Server error"))
                .doThrow(new HttpClient.HttpException(500, "Server error"))
                .doAnswer(invocation -> {
                    latch.countDown();
                    return null;
                })
                .when(segmentUploader).uploadSegment(any(), anyString(), anyString(), anyInt(), anyLong(), anyString());

        // When
        uploadQueue.enqueue(new UploadQueue.SegmentTask(file, "session-1", "device-1", 1, 1024, "checksum"));

        // Then
        assertTrue(latch.await(30, TimeUnit.SECONDS), "Upload should eventually succeed after retries");

        // Verify 3 attempts total (2 failures + 1 success)
        verify(segmentUploader, times(3)).uploadSegment(any(), anyString(), anyString(), anyInt(), anyLong(), anyString());

        // Should NOT be saved to local database (succeeded on retry)
        verify(localDatabase, never()).addPendingSegment(anyString(), anyString(), anyInt(), anyString(), anyLong(), anyString());

        uploadQueue.stop();
    }

    @Test
    void testMaxRetries_movesToLocalDatabase() throws Exception {
        // Given
        uploadQueue.start();

        File file = createTempFile("segment_fail.mp4");
        CountDownLatch latch = new CountDownLatch(1);

        // Fail all attempts
        doThrow(new HttpClient.HttpException(500, "Server error"))
                .when(segmentUploader).uploadSegment(any(), anyString(), anyString(), anyInt(), anyLong(), anyString());

        doAnswer(invocation -> {
            latch.countDown();
            return null;
        }).when(localDatabase).addPendingSegment(anyString(), anyString(), anyInt(), anyString(), anyLong(), anyString());

        // When
        uploadQueue.enqueue(new UploadQueue.SegmentTask(file, "session-1", "device-1", 1, 1024, "checksum"));

        // Then
        assertTrue(latch.await(30, TimeUnit.SECONDS), "Segment should be saved to local database after max retries");

        // Verify max retries + 1 initial attempt = 4 total attempts
        verify(segmentUploader, times(4)).uploadSegment(any(), anyString(), anyString(), anyInt(), anyLong(), anyString());

        // Verify saved to local database
        verify(localDatabase).addPendingSegment(
                eq("session-1"), eq("device-1"), eq(1), eq(file.getAbsolutePath()), eq(1024L), eq("checksum"));

        uploadQueue.stop();
    }

    @Test
    void testEnqueue_whenNotRunning_savesToLocalDatabase() {
        // Given - queue is NOT started
        File file = createTempFile("segment_offline.mp4");

        // When
        uploadQueue.enqueue(new UploadQueue.SegmentTask(file, "session-1", "device-1", 1, 1024, "checksum"));

        // Then
        verify(localDatabase).addPendingSegment(
                eq("session-1"), eq("device-1"), eq(1), eq(file.getAbsolutePath()), eq(1024L), eq("checksum"));
    }

    @Test
    void testStop_waitsForInProgressUploads() throws Exception {
        // Given - control upload timing with latches
        CountDownLatch uploadStarted = new CountDownLatch(1);
        CountDownLatch uploadCanFinish = new CountDownLatch(1);

        doAnswer(invocation -> {
            uploadStarted.countDown();
            uploadCanFinish.await(10, TimeUnit.SECONDS);
            return null;
        }).when(segmentUploader).uploadSegment(any(), anyString(), anyString(), anyInt(), anyLong(), anyString());

        uploadQueue.start();

        File file = createTempFile("segment_1.mp4");
        uploadQueue.enqueue(new UploadQueue.SegmentTask(file, "session-1", "device-1", 1, 1024, "checksum1"));

        // Wait for upload to start
        assertTrue(uploadStarted.await(5, TimeUnit.SECONDS), "Upload should start");

        // Release upload to complete, then stop
        uploadCanFinish.countDown();
        uploadQueue.stop();

        // Then - upload completed, queue is stopped, nothing saved to localDB (success)
        verify(segmentUploader).uploadSegment(eq(file), eq("session-1"), eq("device-1"), eq(1), eq(1024L), eq("checksum1"));
        assertFalse(uploadQueue.isRunning());
        verify(localDatabase, never()).addPendingSegment(anyString(), anyString(), anyInt(), anyString(), anyLong(), anyString());
    }

    @Test
    void testStop_thenEnqueue_savesToLocalDatabase() throws Exception {
        // Given - start and stop the queue
        uploadQueue.start();
        uploadQueue.stop();
        assertFalse(uploadQueue.isRunning());

        // When - enqueue after stop
        File file = createTempFile("segment_after_stop.mp4");
        uploadQueue.enqueue(new UploadQueue.SegmentTask(file, "session-1", "device-1", 1, 1024, "checksum"));

        // Then - should be saved to local database
        verify(localDatabase).addPendingSegment(
                eq("session-1"), eq("device-1"), eq(1), eq(file.getAbsolutePath()), eq(1024L), eq("checksum"));
    }

    private File createTempFile(String name) {
        try {
            File file = tempDir.resolve(name).toFile();
            Files.writeString(file.toPath(), "test content");
            return file;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
