package com.prg.controlplane.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prg.controlplane.dto.response.PageResponse;
import com.prg.controlplane.entity.WebhookDelivery;
import com.prg.controlplane.entity.WebhookSubscription;
import com.prg.controlplane.exception.ResourceNotFoundException;
import com.prg.controlplane.kafka.EventPublisher;
import com.prg.controlplane.kafka.event.WebhookOutboundEvent;
import com.prg.controlplane.repository.WebhookDeliveryRepository;
import com.prg.controlplane.repository.WebhookSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class WebhookService {

    private final WebhookSubscriptionRepository subscriptionRepository;
    private final WebhookDeliveryRepository deliveryRepository;
    private final EventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5)).build();

    // CRUD
    @Transactional
    public WebhookSubscription create(UUID tenantId, UUID createdBy,
                                       String url, List<String> eventTypes, String secret) {
        WebhookSubscription sub = WebhookSubscription.builder()
            .tenantId(tenantId)
            .url(url)
            .eventTypes(eventTypes)
            .secret(secret)
            .active(true)
            .createdBy(createdBy)
            .build();
        sub = subscriptionRepository.save(sub);
        log.info("Webhook subscription created: id={}, tenant={}, url={}", sub.getId(), tenantId, url);
        return sub;
    }

    @Transactional(readOnly = true)
    public List<WebhookSubscription> list(UUID tenantId) {
        return subscriptionRepository.findByTenantId(tenantId);
    }

    @Transactional(readOnly = true)
    public WebhookSubscription get(UUID id, UUID tenantId) {
        return subscriptionRepository.findByIdAndTenantId(id, tenantId)
            .orElseThrow(() -> new ResourceNotFoundException("Webhook not found: " + id, "WEBHOOK_NOT_FOUND"));
    }

    @Transactional
    public WebhookSubscription update(UUID id, UUID tenantId,
                                       String url, List<String> eventTypes, String secret, Boolean active) {
        WebhookSubscription sub = get(id, tenantId);
        if (url != null) sub.setUrl(url);
        if (eventTypes != null) sub.setEventTypes(eventTypes);
        if (secret != null) sub.setSecret(secret);
        if (active != null) sub.setActive(active);
        return subscriptionRepository.save(sub);
    }

    @Transactional
    public void delete(UUID id, UUID tenantId) {
        WebhookSubscription sub = get(id, tenantId);
        subscriptionRepository.delete(sub);
        log.info("Webhook subscription deleted: id={}, tenant={}", id, tenantId);
    }

    @Transactional(readOnly = true)
    public PageResponse<WebhookDelivery> getDeliveries(UUID subscriptionId, int page, int size) {
        Page<WebhookDelivery> deliveryPage = deliveryRepository
            .findBySubscriptionIdOrderByCreatedTsDesc(subscriptionId, PageRequest.of(page, Math.min(size, 100)));
        return PageResponse.<WebhookDelivery>builder()
            .content(deliveryPage.getContent())
            .page(deliveryPage.getNumber())
            .size(deliveryPage.getSize())
            .totalElements(deliveryPage.getTotalElements())
            .totalPages(deliveryPage.getTotalPages())
            .build();
    }

    // Webhook dispatch
    public void publishWebhookIfSubscribed(UUID tenantId, String eventType, Map<String, Object> payload) {
        String eventTypeJson = "\"" + eventType + "\"";
        List<WebhookSubscription> subs = subscriptionRepository
            .findActiveByTenantIdAndEventType(tenantId, eventTypeJson);

        for (WebhookSubscription sub : subs) {
            eventPublisher.publish("webhooks.outbound",
                tenantId + ":" + eventType,
                WebhookOutboundEvent.builder()
                    .eventId(UUID.randomUUID())
                    .subscriptionId(sub.getId())
                    .url(sub.getUrl())
                    .secret(sub.getSecret())
                    .eventType(eventType)
                    .tenantId(tenantId)
                    .payload(payload)
                    .build());
        }
    }

    public void dispatchWebhook(WebhookOutboundEvent event) {
        WebhookDelivery delivery = WebhookDelivery.builder()
            .subscriptionId(event.getSubscriptionId())
            .eventId(event.getEventId())
            .eventType(event.getEventType())
            .status("pending")
            .build();

        try {
            String payloadJson = objectMapper.writeValueAsString(event.getPayload());
            String signature = event.getSecret() != null
                ? hmacSha256(event.getSecret(), payloadJson) : "";

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(event.getUrl()))
                .header("Content-Type", "application/json")
                .header("X-Kadero-Signature", signature)
                .header("X-Kadero-Event", event.getEventType())
                .POST(HttpRequest.BodyPublishers.ofString(payloadJson))
                .timeout(Duration.ofSeconds(10))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            delivery.setStatus("success");
            delivery.setResponseCode(response.statusCode());
            log.info("Webhook delivered: sub={}, url={}, status={}", event.getSubscriptionId(), event.getUrl(), response.statusCode());
        } catch (Exception e) {
            delivery.setStatus("failed");
            delivery.setErrorMessage(e.getMessage());
            log.warn("Webhook delivery failed: sub={}, url={}, error={}", event.getSubscriptionId(), event.getUrl(), e.getMessage());
        }

        delivery.setAttempts(delivery.getAttempts() + 1);
        delivery.setLastAttemptTs(Instant.now());
        deliveryRepository.save(delivery);
    }

    private String hmacSha256(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return "";
        }
    }
}
