package com.prg.playback.service;

import com.prg.playback.entity.RecordingSession;
import com.prg.playback.entity.Segment;
import com.prg.playback.repository.RecordingSessionRepository;
import com.prg.playback.repository.SegmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PlaybackService {

    private final SegmentRepository segmentRepository;
    private final RecordingSessionRepository sessionRepository;
    private final S3Service s3Service;

    public String generatePlaylist(UUID sessionId, UUID tenantId) {
        RecordingSession session = sessionRepository.findByIdAndTenantId(sessionId, tenantId)
            .orElseThrow(() -> new RuntimeException("Session not found: " + sessionId));

        List<Segment> segments = segmentRepository.findConfirmedBySessionIdAndTenantId(sessionId, tenantId);

        if (segments.isEmpty()) {
            throw new RuntimeException("No confirmed segments for session: " + sessionId);
        }

        StringBuilder m3u8 = new StringBuilder();
        m3u8.append("#EXTM3U\n");
        m3u8.append("#EXT-X-VERSION:3\n");
        m3u8.append("#EXT-X-TARGETDURATION:").append(maxDurationSec(segments)).append("\n");
        m3u8.append("#EXT-X-MEDIA-SEQUENCE:0\n");

        for (Segment seg : segments) {
            double durationSec = seg.getDurationMs() != null ? seg.getDurationMs() / 1000.0 : 60.0;
            m3u8.append("#EXTINF:").append(String.format("%.3f", durationSec)).append(",\n");
            m3u8.append("segments/").append(seg.getId()).append("\n");
        }

        if (session.getEndedTs() != null) {
            m3u8.append("#EXT-X-ENDLIST\n");
        }

        log.info("Generated M3U8 playlist: session={}, segments={}", sessionId, segments.size());
        return m3u8.toString();
    }

    public String getSegmentPresignedUrl(UUID segmentId, UUID tenantId) {
        Segment segment = segmentRepository.findByIdAndTenantId(segmentId, tenantId)
            .orElseThrow(() -> new RuntimeException("Segment not found: " + segmentId));

        if (!segment.getTenantId().equals(tenantId)) {
            throw new RuntimeException("Access denied");
        }

        return s3Service.generatePresignedGetUrl(segment.getS3Key());
    }

    public String getSegmentS3Key(UUID segmentId, UUID tenantId) {
        Segment segment = segmentRepository.findByIdAndTenantId(segmentId, tenantId)
            .orElseThrow(() -> new RuntimeException("Segment not found: " + segmentId));

        if (!segment.getTenantId().equals(tenantId)) {
            throw new RuntimeException("Access denied");
        }

        return segment.getS3Key();
    }

    private int maxDurationSec(List<Segment> segments) {
        return segments.stream()
            .mapToInt(s -> s.getDurationMs() != null ? (s.getDurationMs() / 1000 + 1) : 61)
            .max().orElse(61);
    }
}
