package com.prg.controlplane.kafka.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebhookOutboundEvent {
    private UUID eventId;
    private UUID subscriptionId;
    private String url;
    private String secret;
    private String eventType;
    private UUID tenantId;
    private Map<String, Object> payload;
}
