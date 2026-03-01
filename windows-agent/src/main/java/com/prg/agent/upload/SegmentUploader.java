package com.prg.agent.upload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.prg.agent.config.AgentConfig;
import com.prg.agent.util.HttpClient;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Map;

/**
 * Handles the three-step segment upload pipeline:
 * <ol>
 *   <li>POST /api/v1/ingest/presign - get a presigned S3 URL</li>
 *   <li>PUT presigned URL - upload the segment binary to MinIO</li>
 *   <li>POST /api/v1/ingest/confirm - confirm the upload</li>
 * </ol>
 *
 * <p>After successful confirmation, the local segment file is deleted.
 */
public class SegmentUploader {

    private static final Logger log = LoggerFactory.getLogger(SegmentUploader.class);
    private static final String CONTENT_TYPE = "video/mp4";

    private final AgentConfig config;
    private final HttpClient httpClient;
    private final SessionManager sessionManager;

    public SegmentUploader(AgentConfig config, HttpClient httpClient, SessionManager sessionManager) {
        this.config = config;
        this.httpClient = httpClient;
        this.sessionManager = sessionManager;
    }

    /**
     * Uploads a single segment through the presign -> upload -> confirm pipeline.
     *
     * @param segmentFile the local segment file
     * @param sessionId   the recording session ID
     * @param sequenceNum the segment sequence number within the session
     * @param sizeBytes   the file size in bytes
     * @param checksum    the SHA-256 checksum of the file
     * @throws HttpClient.HttpException if any step of the pipeline fails
     */
    public void uploadSegment(File segmentFile, String sessionId, int sequenceNum,
                               long sizeBytes, String checksum) throws HttpClient.HttpException {
        log.info("Uploading segment #{} for session {} ({} bytes)", sequenceNum, sessionId, sizeBytes);

        // Step 1: Get presigned URL
        PresignResponse presignResponse = presign(sessionId, sequenceNum, sizeBytes, checksum);
        log.debug("Got presigned URL for segment {} (segment_id={})",
                sequenceNum, presignResponse.getSegmentId());

        // Step 2: Upload file to S3
        httpClient.uploadFile(presignResponse.getUploadUrl(), segmentFile, CONTENT_TYPE);
        log.debug("File uploaded to S3 for segment {}", sequenceNum);

        // Step 3: Confirm upload
        ConfirmResponse confirmResponse = confirm(presignResponse.getSegmentId(), checksum);
        log.info("Segment #{} upload confirmed (status={}, total_segments={})",
                sequenceNum, confirmResponse.getStatus(),
                confirmResponse.getSessionStats() != null ?
                        confirmResponse.getSessionStats().get("segment_count") : "?");

        // Record stats
        sessionManager.recordSegmentSent(sizeBytes);

        // Delete local file after successful confirmation
        if (segmentFile.exists() && segmentFile.delete()) {
            log.debug("Deleted local segment file: {}", segmentFile.getName());
        }
    }

    /**
     * Step 1: Request a presigned upload URL from the ingest gateway.
     */
    private PresignResponse presign(String sessionId, int sequenceNum,
                                     long sizeBytes, String checksum) throws HttpClient.HttpException {
        PresignRequest request = new PresignRequest();
        request.setSessionId(sessionId);
        request.setSequenceNum(sequenceNum);
        request.setSizeBytes(sizeBytes);
        request.setChecksumSha256(checksum);
        request.setContentType(CONTENT_TYPE);
        request.setMetadata(Map.of(
                "fps", config.getCaptureFps(),
                "codec", "h264",
                "quality", config.getCaptureQuality()
        ));

        String url = config.getServerIngestUrl() + "/presign";
        return httpClient.authPost(url, request, PresignResponse.class);
    }

    /**
     * Step 3: Confirm the upload with the ingest gateway.
     */
    private ConfirmResponse confirm(String segmentId, String checksum) throws HttpClient.HttpException {
        ConfirmRequest request = new ConfirmRequest();
        request.setSegmentId(segmentId);
        request.setChecksumSha256(checksum);

        String url = config.getServerIngestUrl() + "/confirm";
        return httpClient.authPost(url, request, ConfirmResponse.class);
    }

    // ---- DTOs ----

    @Data
    @NoArgsConstructor
    public static class PresignRequest {
        @JsonProperty("session_id")
        private String sessionId;
        @JsonProperty("sequence_num")
        private int sequenceNum;
        @JsonProperty("size_bytes")
        private long sizeBytes;
        @JsonProperty("checksum_sha256")
        private String checksumSha256;
        @JsonProperty("content_type")
        private String contentType;
        private Map<String, Object> metadata;
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PresignResponse {
        @JsonProperty("segment_id")
        private String segmentId;
        @JsonProperty("upload_url")
        private String uploadUrl;
        @JsonProperty("upload_method")
        private String uploadMethod;
        @JsonProperty("upload_headers")
        private Map<String, String> uploadHeaders;
        @JsonProperty("expires_in_sec")
        private int expiresInSec;
    }

    @Data
    @NoArgsConstructor
    public static class ConfirmRequest {
        @JsonProperty("segment_id")
        private String segmentId;
        @JsonProperty("checksum_sha256")
        private String checksumSha256;
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ConfirmResponse {
        @JsonProperty("segment_id")
        private String segmentId;
        private String status;
        @JsonProperty("session_stats")
        private Map<String, Object> sessionStats;
    }
}
