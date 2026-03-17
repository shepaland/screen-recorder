package com.prg.search.consumer;

import com.prg.search.dto.SegmentConfirmedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class SegmentIndexerConsumer {

    private final OpenSearchClient openSearchClient;

    @KafkaListener(topics = "segments.ingest", groupId = "search-indexer")
    public void onMessage(ConsumerRecord<String, SegmentConfirmedEvent> record,
                          Acknowledgment ack) {
        SegmentConfirmedEvent event = record.value();
        if (event == null) {
            ack.acknowledge();
            return;
        }

        try {
            String indexName = "segments-" +
                event.getTimestamp().atZone(ZoneOffset.UTC)
                    .format(DateTimeFormatter.ofPattern("yyyy-MM"));

            openSearchClient.index(i -> i
                .index(indexName)
                .id(event.getSegmentId().toString())
                .document(toDocument(event))
            );

            ack.acknowledge();
            log.debug("Indexed segment {} into {}", event.getSegmentId(), indexName);
        } catch (Exception e) {
            log.error("Failed to index segment {}: {}", event.getSegmentId(), e.getMessage());
            throw new RuntimeException(e);
        }
    }

    private Map<String, Object> toDocument(SegmentConfirmedEvent event) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("tenant_id", event.getTenantId().toString());
        doc.put("device_id", event.getDeviceId().toString());
        doc.put("session_id", event.getSessionId().toString());
        doc.put("segment_id", event.getSegmentId().toString());
        doc.put("sequence_num", event.getSequenceNum());
        doc.put("s3_key", event.getS3Key());
        doc.put("size_bytes", event.getSizeBytes());
        doc.put("duration_ms", event.getDurationMs());
        doc.put("checksum_sha256", event.getChecksumSha256());
        doc.put("timestamp", event.getTimestamp().toString());
        if (event.getMetadata() != null) {
            doc.put("metadata", event.getMetadata());
        }
        return doc;
    }
}
