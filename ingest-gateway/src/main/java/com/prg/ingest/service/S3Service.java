package com.prg.ingest.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class S3Service {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;

    @Value("${prg.s3.bucket}")
    private String bucket;

    @Value("${prg.s3.presign-expiry-sec}")
    private int presignExpirySec;

    @PostConstruct
    public void init() {
        ensureBucketExists();
    }

    public String generatePresignedPutUrl(String key, String contentType, long contentLength) {
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType != null ? contentType : "video/mp4")
                .contentLength(contentLength)
                .build();

        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .putObjectRequest(putObjectRequest)
                .signatureDuration(Duration.ofSeconds(presignExpirySec))
                .build();

        String url = s3Presigner.presignPutObject(presignRequest).url().toString();
        log.debug("Generated presigned PUT URL for key={}", key);
        return url;
    }

    public boolean objectExists(String key) {
        try {
            s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            log.warn("Error checking object existence for key={}: {}", key, e.getMessage());
            return false;
        }
    }

    public long getObjectSize(String key) {
        try {
            HeadObjectResponse response = s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build());
            return response.contentLength();
        } catch (S3Exception e) {
            log.warn("Error getting object size for key={}: {}", key, e.getMessage());
            return -1;
        }
    }

    public void ensureBucketExists() {
        try {
            s3Client.headBucket(HeadBucketRequest.builder()
                    .bucket(bucket)
                    .build());
            log.info("S3 bucket '{}' exists", bucket);
        } catch (NoSuchBucketException e) {
            log.info("S3 bucket '{}' does not exist, creating...", bucket);
            s3Client.createBucket(CreateBucketRequest.builder()
                    .bucket(bucket)
                    .build());
            log.info("S3 bucket '{}' created", bucket);
        } catch (S3Exception e) {
            log.error("Error checking/creating S3 bucket '{}': {}", bucket, e.getMessage());
        }
    }

    /**
     * Batch delete multiple objects from S3.
     * Returns a list of keys that failed to delete (empty list on full success).
     * Already-deleted keys are NOT treated as errors (idempotent).
     */
    public List<String> deleteObjects(List<String> s3Keys) {
        if (s3Keys.isEmpty()) {
            return List.of();
        }

        List<ObjectIdentifier> objectIds = s3Keys.stream()
                .map(key -> ObjectIdentifier.builder().key(key).build())
                .toList();

        Delete delete = Delete.builder()
                .objects(objectIds)
                .quiet(false)
                .build();

        DeleteObjectsResponse response = s3Client.deleteObjects(
                DeleteObjectsRequest.builder()
                        .bucket(bucket)
                        .delete(delete)
                        .build());

        List<String> failedKeys = new ArrayList<>();
        if (response.errors() != null) {
            for (S3Error error : response.errors()) {
                log.warn("Failed to delete S3 object: key={}, code={}, message={}",
                        error.key(), error.code(), error.message());
                failedKeys.add(error.key());
            }
        }

        log.info("Deleted {} objects from S3, {} failed",
                s3Keys.size() - failedKeys.size(), failedKeys.size());
        return failedKeys;
    }

    /**
     * Get an InputStream for an S3 object.
     * Caller is responsible for closing the returned stream.
     */
    public ResponseInputStream<GetObjectResponse> getObject(String key) {
        return s3Client.getObject(GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build());
    }

    public int getPresignExpirySec() {
        return presignExpirySec;
    }

    public String getBucket() {
        return bucket;
    }
}
