package com.prg.ingest.kafka.consumer;

import com.prg.ingest.entity.RecordingSession;
import com.prg.ingest.entity.Segment;
import com.prg.ingest.kafka.event.SegmentConfirmedEvent;
import com.prg.ingest.repository.RecordingSessionRepository;
import com.prg.ingest.repository.SegmentRepository;
import com.prg.ingest.service.S3Service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Component
@ConditionalOnProperty(name = "kafka.segment-writer.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class SegmentWriterConsumer {

    private final SegmentRepository segmentRepository;
    private final RecordingSessionRepository sessionRepository;
    private final S3Service s3Service;

    @Value("${kafka.confirm-mode:sync}")
    private String confirmMode;

    @KafkaListener(
        topics = "segments.ingest",
        groupId = "segment-writer",
        containerFactory = "batchKafkaListenerContainerFactory"
    )
    public void onBatch(List<ConsumerRecord<String, SegmentConfirmedEvent>> records,
                        Acknowledgment ack) {
        log.info("segment-writer: processing batch of {} records", records.size());

        List<SegmentConfirmedEvent> events = records.stream()
            .map(ConsumerRecord::value)
            .filter(v -> v != null)
            .toList();

        if (events.isEmpty()) {
            log.warn("segment-writer: batch had no valid events, skipping");
            ack.acknowledge();
            return;
        }

        try {
            processBatch(events);
            ack.acknowledge();
            log.info("segment-writer: batch of {} committed", events.size());
        } catch (Exception e) {
            log.error("segment-writer: batch failed, will retry: {}", e.getMessage(), e);
            throw e;
        }
    }

    @Transactional
    public void processBatch(List<SegmentConfirmedEvent> events) {
        boolean kafkaOnly = "kafka-only".equals(confirmMode);

        for (SegmentConfirmedEvent event : events) {
            Segment segment = toSegmentEntity(event);
            segmentRepository.findByIdAndTenantId(segment.getId(), segment.getTenantId())
                .ifPresentOrElse(
                    existing -> {
                        existing.setStatus("confirmed");
                        if (segment.getSizeBytes() != null) existing.setSizeBytes(segment.getSizeBytes());
                        if (segment.getChecksumSha256() != null) existing.setChecksumSha256(segment.getChecksumSha256());
                        segmentRepository.save(existing);
                    },
                    () -> segmentRepository.save(segment)
                );
        }

        // In kafka-only mode, consumer is the sole writer of session stats
        if (kafkaOnly) {
            updateSessionStats(events);
        }

        log.info("segment-writer: wrote {} segments (kafka-only={})", events.size(), kafkaOnly);

        asyncS3Validation(events);
    }

    private void updateSessionStats(List<SegmentConfirmedEvent> events) {
        Map<UUID, List<SegmentConfirmedEvent>> bySession = events.stream()
            .collect(Collectors.groupingBy(SegmentConfirmedEvent::getSessionId));

        for (var entry : bySession.entrySet()) {
            UUID sessionId = entry.getKey();
            List<SegmentConfirmedEvent> sessionEvents = entry.getValue();
            UUID tenantId = sessionEvents.get(0).getTenantId();

            int deltaCount = sessionEvents.size();
            long deltaBytes = sessionEvents.stream()
                .mapToLong(e -> e.getSizeBytes() != null ? e.getSizeBytes() : 0)
                .sum();
            long deltaDuration = sessionEvents.stream()
                .mapToLong(e -> e.getDurationMs() != null ? e.getDurationMs() : 0)
                .sum();

            sessionRepository.findByIdAndTenantId(sessionId, tenantId)
                .ifPresentOrElse(
                    session -> {
                        session.setSegmentCount(session.getSegmentCount() + deltaCount);
                        session.setTotalBytes(session.getTotalBytes() + deltaBytes);
                        session.setTotalDurationMs(session.getTotalDurationMs() + deltaDuration);
                        sessionRepository.save(session);
                        log.debug("segment-writer: updated session {} stats: +{} segments, +{} bytes",
                            sessionId, deltaCount, deltaBytes);
                    },
                    () -> log.warn("segment-writer: session {} not found for stats update", sessionId)
                );
        }
    }

    private void asyncS3Validation(List<SegmentConfirmedEvent> events) {
        CompletableFuture.runAsync(() -> {
            int missing = 0;
            for (SegmentConfirmedEvent event : events) {
                try {
                    if (!s3Service.objectExists(event.getS3Key())) {
                        log.warn("segment-writer: S3 missing: segment={}, key={}",
                            event.getSegmentId(), event.getS3Key());
                        missing++;
                    }
                } catch (Exception e) {
                    log.warn("segment-writer: S3 check error: segment={}, err={}",
                        event.getSegmentId(), e.getMessage());
                }
            }
            if (missing > 0) {
                log.warn("segment-writer: {}/{} segments missing in S3", missing, events.size());
            }
        });
    }

    private Segment toSegmentEntity(SegmentConfirmedEvent event) {
        return Segment.builder()
            .id(event.getSegmentId())
            .createdTs(event.getTimestamp() != null ? event.getTimestamp() : Instant.now())
            .tenantId(event.getTenantId())
            .deviceId(event.getDeviceId())
            .sessionId(event.getSessionId())
            .sequenceNum(event.getSequenceNum())
            .s3Bucket("prg-segments")
            .s3Key(event.getS3Key())
            .sizeBytes(event.getSizeBytes())
            .durationMs(event.getDurationMs())
            .checksumSha256(event.getChecksumSha256())
            .status("confirmed")
            .metadata(event.getMetadata() != null ? event.getMetadata() : Map.of())
            .build();
    }
}
