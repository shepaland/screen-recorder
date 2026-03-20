# Фаза 7: Webhook Worker

> **Влияние на текущую систему: НОЛЬ**
> Новый consumer group + API endpoints в control-plane. Внешние интеграции CRM/АТС.

---

## Цель

Webhook-подписки (CRUD), dispatch событий через Kafka, HTTP POST с HMAC-подписью во внешние системы. Retry с exponential backoff. Delivery log.

## Предусловия

- Фаза 2 завершена: события в Kafka
- control-plane задеплоен на test

---

## Задачи

### KFK-060: Flyway миграция — webhook_subscriptions + webhook_deliveries

**Таблица `webhook_subscriptions`:**
```sql
CREATE TABLE webhook_subscriptions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL,
    url             VARCHAR(2048) NOT NULL,
    event_types     JSONB NOT NULL DEFAULT '[]',   -- ["segment.confirmed", "device.online"]
    secret          VARCHAR(256),                   -- для HMAC-SHA256 подписи
    active          BOOLEAN NOT NULL DEFAULT true,
    created_ts      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_ts      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by      UUID
);
CREATE INDEX idx_webhook_subs_tenant ON webhook_subscriptions(tenant_id);
CREATE INDEX idx_webhook_subs_active ON webhook_subscriptions(tenant_id, active) WHERE active = true;
```

**Таблица `webhook_deliveries`:**
```sql
CREATE TABLE webhook_deliveries (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subscription_id UUID NOT NULL REFERENCES webhook_subscriptions(id),
    event_id        UUID NOT NULL,
    event_type      VARCHAR(64) NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'pending',  -- pending, success, failed
    response_code   INTEGER,
    attempts        INTEGER NOT NULL DEFAULT 0,
    last_attempt_ts TIMESTAMPTZ,
    error_message   TEXT,
    created_ts      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_webhook_del_sub ON webhook_deliveries(subscription_id, created_ts DESC);
```

**Permissions** (добавить в auth-service миграцию):
```sql
INSERT INTO permissions (id, name, description) VALUES
    (gen_random_uuid(), 'WEBHOOKS:READ', 'View webhook subscriptions'),
    (gen_random_uuid(), 'WEBHOOKS:MANAGE', 'Create/update/delete webhook subscriptions');
```

**Файлы:** `auth-service/src/main/resources/db/migration/V38__webhook_subscriptions.sql`

---

### KFK-061: Control-plane — CRUD API webhooks

**Endpoints:**
```
POST   /api/v1/webhooks                    — создать подписку
GET    /api/v1/webhooks                    — список подписок (tenant)
GET    /api/v1/webhooks/{id}               — детали подписки
PUT    /api/v1/webhooks/{id}               — обновить подписку
DELETE /api/v1/webhooks/{id}               — удалить подписку
GET    /api/v1/webhooks/{id}/deliveries    — история доставок
```

**Request body (POST/PUT):**
```json
{
  "url": "https://crm.example.com/hooks/kadero",
  "event_types": ["segment.confirmed", "device.online", "device.offline"],
  "secret": "my-hmac-secret",
  "active": true
}
```

**Файлы:**
- `control-plane/src/main/java/com/prg/controlplane/entity/WebhookSubscription.java`
- `control-plane/src/main/java/com/prg/controlplane/entity/WebhookDelivery.java`
- `control-plane/src/main/java/com/prg/controlplane/repository/WebhookSubscriptionRepository.java`
- `control-plane/src/main/java/com/prg/controlplane/repository/WebhookDeliveryRepository.java`
- `control-plane/src/main/java/com/prg/controlplane/service/WebhookService.java`
- `control-plane/src/main/java/com/prg/controlplane/controller/WebhookController.java`

---

### KFK-062: Publish webhooks.outbound

При получении события (segment.confirmed, device.online) — проверить есть ли активные подписки для tenant + event_type, и если да — publish в `webhooks.outbound`:

```java
// В EventPublisher или отдельном WebhookPublisher
public void publishWebhookIfSubscribed(UUID tenantId, String eventType, Object payload) {
    List<WebhookSubscription> subs = subscriptionRepository
        .findByTenantIdAndActiveAndEventType(tenantId, true, eventType);

    for (WebhookSubscription sub : subs) {
        eventPublisher.publish("webhooks.outbound",
            tenantId + ":" + eventType,
            WebhookOutboundEvent.builder()
                .eventId(UUID.randomUUID())
                .subscriptionId(sub.getId())
                .url(sub.getUrl())
                .secret(sub.getSecret())
                .eventType(eventType)
                .payload(payload)
                .build());
    }
}
```

---

### KFK-063: Webhook dispatcher consumer

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class WebhookDispatcherConsumer {

    private final RestTemplate restTemplate;
    private final WebhookDeliveryRepository deliveryRepository;

    @KafkaListener(topics = "webhooks.outbound", groupId = "webhook-dispatcher")
    public void onMessage(ConsumerRecord<String, WebhookOutboundEvent> record,
                          Acknowledgment ack) {
        WebhookOutboundEvent event = record.value();
        WebhookDelivery delivery = createDeliveryRecord(event);

        try {
            // HMAC-SHA256 подпись
            String signature = hmacSha256(event.getSecret(),
                objectMapper.writeValueAsString(event.getPayload()));

            HttpHeaders headers = new HttpHeaders();
            headers.set("Content-Type", "application/json");
            headers.set("X-Kadero-Signature", signature);
            headers.set("X-Kadero-Event", event.getEventType());

            ResponseEntity<String> response = restTemplate.exchange(
                event.getUrl(), HttpMethod.POST,
                new HttpEntity<>(event.getPayload(), headers), String.class);

            delivery.setStatus("success");
            delivery.setResponseCode(response.getStatusCode().value());

        } catch (Exception e) {
            delivery.setStatus("failed");
            delivery.setErrorMessage(e.getMessage());
            log.warn("Webhook delivery failed: sub={}, url={}, error={}",
                event.getSubscriptionId(), event.getUrl(), e.getMessage());
        }

        delivery.setAttempts(delivery.getAttempts() + 1);
        delivery.setLastAttemptTs(Instant.now());
        deliveryRepository.save(delivery);
        ack.acknowledge();
    }
}
```

**Retry:** Для failed deliveries — отдельный scheduled job проверяет deliveries с status=failed и attempts < 3, повторно publish в `webhooks.outbound` с exponential backoff (1 мин, 5 мин, 30 мин).

---

### KFK-064: Webhook delivery log

Уже описан в KFK-060 (таблица `webhook_deliveries`). API для просмотра:

```
GET /api/v1/webhooks/{id}/deliveries?page=0&size=20
```

Response: paginated list с status, response_code, attempts, timestamps.

---

### KFK-065: Верификация на test

```bash
# 1. Создать подписку
curl -X POST "https://services-test.shepaland.ru/screenrecorder/api/cp/v1/webhooks" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"url":"https://webhook.site/unique-id","event_types":["segment.confirmed"],"active":true}'

# 2. Confirm сегмент с агента

# 3. Проверить webhook.site — должен получить POST с payload и X-Kadero-Signature

# 4. Проверить delivery log
curl "https://services-test.shepaland.ru/screenrecorder/api/cp/v1/webhooks/{id}/deliveries" \
  -H "Authorization: Bearer $TOKEN"
```

---

## Чеклист завершения фазы

- [ ] Миграция webhook_subscriptions + webhook_deliveries applied
- [ ] CRUD API webhooks работает
- [ ] При событии → POST на configured URL с HMAC подписью
- [ ] Delivery log записывается (status, response_code, attempts)
- [ ] Retry для failed deliveries (3 попытки, exponential backoff)
- [ ] **Текущая система не затронута**
