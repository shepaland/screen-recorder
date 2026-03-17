package com.prg.controlplane.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "kafka.dual-write.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class EventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publish(String topic, String key, Object event) {
        try {
            kafkaTemplate.send(topic, key, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.warn("Kafka publish failed: topic={}, key={}, error={}",
                            topic, key, ex.getMessage());
                    } else {
                        log.debug("Kafka published: topic={}, key={}, offset={}",
                            topic, key, result.getRecordMetadata().offset());
                    }
                });
        } catch (Exception e) {
            log.warn("Kafka publish error (suppressed): topic={}, key={}, error={}",
                topic, key, e.getMessage());
        }
    }
}
