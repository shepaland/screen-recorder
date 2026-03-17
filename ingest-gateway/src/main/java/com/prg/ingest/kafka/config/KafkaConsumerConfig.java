package com.prg.ingest.kafka.config;

import com.prg.ingest.kafka.event.SegmentConfirmedEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

@Configuration
@ConditionalOnProperty(name = "kafka.segment-writer.enabled", havingValue = "true")
public class KafkaConsumerConfig {

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, SegmentConfirmedEvent>
        batchKafkaListenerContainerFactory(ConsumerFactory<String, SegmentConfirmedEvent> cf) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, SegmentConfirmedEvent>();
        factory.setConsumerFactory(cf);
        factory.setBatchListener(true);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        factory.setConcurrency(2);
        return factory;
    }
}
