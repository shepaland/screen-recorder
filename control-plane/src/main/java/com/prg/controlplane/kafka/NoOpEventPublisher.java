package com.prg.controlplane.kafka;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "kafka.dual-write.enabled", havingValue = "false", matchIfMissing = true)
@Slf4j
public class NoOpEventPublisher extends EventPublisher {

    public NoOpEventPublisher() {
        super(null);
    }

    @Override
    public void publish(String topic, String key, Object event) {
        // No-op: dual-write disabled
    }
}
