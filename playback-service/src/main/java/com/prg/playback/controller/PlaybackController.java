package com.prg.playback.controller;

import com.prg.playback.filter.JwtValidationFilter;
import com.prg.playback.security.DevicePrincipal;
import com.prg.playback.service.PlaybackService;
import com.prg.playback.service.S3Service;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/playback")
@RequiredArgsConstructor
@Slf4j
public class PlaybackController {

    private final PlaybackService playbackService;
    private final S3Service s3Service;

    @GetMapping(value = "/sessions/{sessionId}/playlist.m3u8",
                produces = "application/vnd.apple.mpegurl")
    public ResponseEntity<String> getPlaylist(
            @PathVariable UUID sessionId,
            HttpServletRequest request) {
        DevicePrincipal principal = getPrincipal(request);
        String playlist = playbackService.generatePlaylist(sessionId, principal.getTenantId());
        return ResponseEntity.ok()
            .header("Cache-Control", "no-cache")
            .body(playlist);
    }

    /**
     * Stream segment video from S3 directly to the client (proxy mode).
     * Previous implementation returned 302 redirect to internal MinIO presigned URL,
     * which is not accessible from the browser (internal k8s DNS).
     */
    @GetMapping("/sessions/{sessionId}/segments/{segmentId}")
    public void getSegment(
            @PathVariable UUID sessionId,
            @PathVariable UUID segmentId,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        DevicePrincipal principal = getPrincipal(request);
        String s3Key = playbackService.getSegmentS3Key(segmentId, principal.getTenantId());

        response.setContentType("video/mp4");
        response.setHeader("Cache-Control", "private, max-age=300");

        try (var s3Stream = s3Service.getObject(s3Key)) {
            Long contentLength = s3Stream.response().contentLength();
            if (contentLength != null) {
                response.setContentLengthLong(contentLength);
            }
            s3Stream.transferTo(response.getOutputStream());
        }
    }

    private DevicePrincipal getPrincipal(HttpServletRequest request) {
        DevicePrincipal principal = (DevicePrincipal) request.getAttribute(
                JwtValidationFilter.DEVICE_PRINCIPAL_ATTRIBUTE);
        if (principal == null) {
            throw new IllegalStateException("DevicePrincipal not found");
        }
        return principal;
    }
}
