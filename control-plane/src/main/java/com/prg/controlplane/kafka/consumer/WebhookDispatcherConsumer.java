package com.prg.controlplane.kafka.consumer;

import com.prg.controlplane.kafka.event.WebhookOutboundEvent;
import com.prg.controlplane.service.WebhookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "kafka.webhook-dispatcher.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class WebhookDispatcherConsumer {

    private final WebhookService webhookService;

    @KafkaListener(topics = "webhooks.outbound", groupId = "webhook-dispatcher")
    public void onMessage(ConsumerRecord<String, WebhookOutboundEvent> record,
                          Acknowledgment ack) {
        WebhookOutboundEvent event = record.value();
        if (event == null) {
            ack.acknowledge();
            return;
        }

        try {
            webhookService.dispatchWebhook(event);
        } catch (Exception e) {
            log.error("Webhook dispatch error: sub={}, error={}", event.getSubscriptionId(), e.getMessage());
        }

        ack.acknowledge();
    }
}
