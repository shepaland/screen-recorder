package com.prg.playback.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.time.Duration;

@Service
@RequiredArgsConstructor
@Slf4j
public class S3Service {

    private final S3Presigner s3Presigner;
    private final S3Client s3Client;

    @Value("${prg.s3.bucket}")
    private String bucket;

    @Value("${prg.s3.presign-expiry-sec}")
    private int presignExpirySec;

    public String generatePresignedGetUrl(String key) {
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
            .signatureDuration(Duration.ofSeconds(presignExpirySec))
            .getObjectRequest(g -> g.bucket(bucket).key(key))
            .build();

        String url = s3Presigner.presignGetObject(presignRequest).url().toString();
        log.debug("Generated presigned GET URL for key={}", key);
        return url;
    }

    public ResponseInputStream<GetObjectResponse> getObject(String key) {
        GetObjectRequest request = GetObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .build();
        return s3Client.getObject(request);
    }
}
