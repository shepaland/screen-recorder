# Фаза 2: Dual-Write (переходный период)

> **Влияние на текущую систему: НОЛЬ**
> Существующий sync path НЕ трогается. Kafka publish — дополнительный fire-and-forget.
> Feature flag `kafka.dual-write.enabled=false` по умолчанию.

---

## Цель

ingest-gateway и control-plane начинают **дополнительно** публиковать события в Kafka после каждого успешного DB commit. Текущий синхронный путь не меняется. Если Kafka недоступна — WARN в лог, основной путь работает.

## Предусловия

- Фаза 1 завершена: Kafka Running, topics созданы
- Текущие сервисы работают стабильно

---

## Задачи

### KFK-010: Spring Kafka зависимость

**Что сделать:**

Добавить в `ingest-gateway/pom.xml` и `control-plane/pom.xml`:

```xml
<dependency>
    <groupId>org.springframework.kafka</groupId>
    <artifactId>spring-kafka</artifactId>
</dependency>
```

Версия наследуется от `spring-boot-starter-parent` 3.3.0 (Kafka 3.7.x).

**Файлы:**
- `ingest-gateway/pom.xml`
- `control-plane/pom.xml`

**Критерий приёмки:** `./mvnw compile` проходит в обоих сервисах.

---

### KFK-011: Feature flags

**Что сделать:**

Добавить в `application.yml` обоих сервисов:

```yaml
# ingest-gateway/src/main/resources/application.yml
kafka:
  dual-write:
    enabled: ${KAFKA_DUAL_WRITE:false}
  confirm-mode: ${KAFKA_CONFIRM_MODE:sync}   # sync | kafka-only (Фаза 4)
  bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}

spring:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
      acks: all
      retries: 3
      properties:
        enable.idempotence: true
        max.in.flight.requests.per.connection: 1
```

**Поведение по умолчанию:** `KAFKA_DUAL_WRITE=false` → Spring Kafka producer **создаётся**, но `EventPublisher` **не вызывается**. Сервис работает точно как раньше.

**Файлы:**
- `ingest-gateway/src/main/resources/application.yml`
- `control-plane/src/main/resources/application.yml`

**Критерий приёмки:** Сервис стартует локально с `KAFKA_DUAL_WRITE=false`, без Kafka-брокера → ошибок нет (producer не подключается, если не используется).

---

### KFK-012: EventPublisher компонент

**Что сделать:**

Создать в каждом сервисе (или в shared module):

```java
// ingest-gateway/src/main/java/com/prg/ingest/kafka/EventPublisher.java

@Component
@ConditionalOnProperty(name = "kafka.dual-write.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class EventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * Fire-and-forget publish. НИКОГДА не бросает exception.
     * При ошибке — WARN в лог.
     */
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
            // НЕ пробрасываем exception — основной path не затронут
        }
    }
}
```

И No-op заглушку, когда flag=false:

```java
// ingest-gateway/src/main/java/com/prg/ingest/kafka/NoOpEventPublisher.java

@Component
@ConditionalOnProperty(name = "kafka.dual-write.enabled", havingValue = "false", matchIfMissing = true)
public class NoOpEventPublisher extends EventPublisher {
    public NoOpEventPublisher() { super(null); }

    @Override
    public void publish(String topic, String key, Object event) {
        // No-op
    }
}
```

**Альтернатива:** использовать `Optional<EventPublisher>` inject вместо No-op.

**Файлы:**
- `ingest-gateway/src/main/java/com/prg/ingest/kafka/EventPublisher.java`
- `ingest-gateway/src/main/java/com/prg/ingest/kafka/NoOpEventPublisher.java`
- (аналогично для control-plane)

**Критерий приёмки:** С `KAFKA_DUAL_WRITE=false` — NoOpEventPublisher загружается, publish() = NOP. С `true` — EventPublisher + KafkaTemplate.

---

### KFK-013: Ingest-gw — dual-write в confirm()

**Что сделать:**

Модифицировать `IngestService.confirm()` (line ~99-160):

```java
// СУЩЕСТВУЮЩИЙ КОД confirm() НЕ МЕНЯЕТСЯ.
// Добавляем ОДНУ строку после @Transactional метода.

@Transactional
public ConfirmResponse confirm(ConfirmRequest request, DevicePrincipal principal) {
    // ... существующая логика (lines 99-155) — НЕ ТРОГАЕМ ...

    Segment confirmedSegment = segmentRepository.save(segment);
    RecordingSession updatedSession = sessionRepository.save(session);

    // >>> НОВОЕ: dual-write в Kafka (fire-and-forget, после DB commit) <<<
    eventPublisher.publish("segments.ingest",
        principal.getDeviceId().toString(),
        SegmentConfirmedEvent.builder()
            .eventId(UUID.randomUUID())
            .timestamp(Instant.now())
            .tenantId(principal.getTenantId())
            .deviceId(principal.getDeviceId())
            .sessionId(confirmedSegment.getSessionId())
            .segmentId(confirmedSegment.getId())
            .sequenceNum(confirmedSegment.getSequenceNum())
            .s3Key(confirmedSegment.getS3Key())
            .sizeBytes(confirmedSegment.getSizeBytes())
            .durationMs(confirmedSegment.getDurationMs())
            .checksumSha256(confirmedSegment.getChecksumSha256())
            .metadata(confirmedSegment.getMetadata())
            .build());

    return buildConfirmResponse(updatedSession);
}
```

Создать DTO:

```java
// ingest-gateway/src/main/java/com/prg/ingest/kafka/event/SegmentConfirmedEvent.java

@Data @Builder
public class SegmentConfirmedEvent {
    private UUID eventId;
    private Instant timestamp;
    private UUID tenantId;
    private UUID deviceId;
    private UUID sessionId;
    private UUID segmentId;
    private Integer sequenceNum;
    private String s3Key;
    private Long sizeBytes;
    private Integer durationMs;
    private String checksumSha256;
    private Map<String, Object> metadata;
}
```

**ВАЖНО:** `eventPublisher.publish()` вызывается **после** `@Transactional` commit (Spring вызывает commit при выходе из метода). Если нужна гарантия "только после commit" — использовать `TransactionSynchronizationManager.registerSynchronization(afterCommit)`.

**Файлы:**
- `ingest-gateway/src/main/java/com/prg/ingest/service/IngestService.java` (добавить ~5 строк)
- `ingest-gateway/src/main/java/com/prg/ingest/kafka/event/SegmentConfirmedEvent.java` (новый)

**Критерий приёмки:** С `KAFKA_DUAL_WRITE=false` — confirm() работает как раньше (NoOp). С `true` — событие появляется в topic `segments.ingest`.

---

### KFK-014: Control-plane — dual-write commands + device events

**Что сделать:**

1. В `DeviceCommandService.createCommand()` (line ~34-59) — после DB save:
   ```java
   eventPublisher.publish("commands.issued",
       deviceId.toString(),
       CommandIssuedEvent.builder()
           .commandId(command.getId())
           .commandType(command.getCommandType())
           .tenantId(tenantId)
           .deviceId(deviceId)
           .payload(command.getPayload())
           .createdBy(createdBy)
           .createdAt(command.getCreatedTs())
           .expiresAt(command.getExpiresAt())
           .build());
   ```

2. В `DeviceService.processHeartbeat()` (line ~187-260) — при смене статуса:
   ```java
   if (statusChanged) {
       eventPublisher.publish("device.events",
           deviceId.toString(),
           DeviceStatusEvent.builder()
               .eventId(UUID.randomUUID())
               .eventType(isOnline ? "device.online" : "device.offline")
               .timestamp(Instant.now())
               .tenantId(principal.getTenantId())
               .deviceId(deviceId)
               .hostname(device.getHostname())
               .agentVersion(request.getAgentVersion())
               .build());
   }
   ```

**Файлы:**
- `control-plane/src/main/java/com/prg/controlplane/service/DeviceCommandService.java`
- `control-plane/src/main/java/com/prg/controlplane/service/DeviceService.java`
- `control-plane/src/main/java/com/prg/controlplane/kafka/EventPublisher.java` (новый, копия)
- `control-plane/src/main/java/com/prg/controlplane/kafka/event/*.java` (DTOs)

**Критерий приёмки:** При создании команды → событие в `commands.issued`. При heartbeat с изменением статуса → событие в `device.events`.

---

### KFK-015: Включение dual-write на test

**Что сделать:**

1. Собрать и задеплоить ingest-gateway и control-plane на test
2. Обновить ConfigMap: `KAFKA_DUAL_WRITE=true`
3. Rollout restart обоих сервисов
4. Проверить:
   ```bash
   # На shepaland-cloud
   sudo k3s kubectl -n test-screen-record exec -it deployment/kafka -- \
     kafka-console-consumer.sh --bootstrap-server localhost:9092 \
     --topic segments.ingest --from-beginning
   # Confirm сегмент с агента → событие должно появиться

   sudo k3s kubectl -n test-screen-record exec -it deployment/kafka -- \
     kafka-console-consumer.sh --bootstrap-server localhost:9092 \
     --topic device.events --from-beginning
   # Heartbeat → device.online должен появиться
   ```

5. Проверить что основной путь не сломан:
   - Агент подключается, heartbeat OK
   - Presign/confirm работают
   - Dashboard показывает данные

**Критерий приёмки:** События в Kafka topics + основной функционал не сломан.

---

### KFK-016: Интеграционные тесты

**Что сделать:**

Добавить тесты с Testcontainers:

```java
@SpringBootTest
@Testcontainers
class IngestServiceKafkaTest {

    @Container
    static KafkaContainer kafka = new KafkaContainer(
        DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    @Test
    void confirm_withKafkaEnabled_publishesEvent() {
        // Arrange: presign → upload → confirm
        // Assert: событие в topic segments.ingest
    }

    @Test
    void confirm_withKafkaDisabled_doesNotPublish() {
        // kafka.dual-write.enabled=false
        // Assert: topic пуст
    }

    @Test
    void confirm_withKafkaDown_succeedsWithWarning() {
        // Остановить Kafka container
        // Confirm → 200 OK (sync path работает)
        // Лог содержит WARN
    }
}
```

**Файлы:**
- `ingest-gateway/src/test/java/com/prg/ingest/service/IngestServiceKafkaTest.java`
- `control-plane/src/test/java/.../DeviceCommandServiceKafkaTest.java`

**Критерий приёмки:** 3 теста проходят. Graceful degradation подтверждена.

---

## Чеклист завершения фазы

- [ ] `spring-kafka` в pom.xml обоих сервисов
- [ ] Feature flag `kafka.dual-write.enabled` работает (true/false)
- [ ] confirm() → событие в `segments.ingest`
- [ ] createCommand() → событие в `commands.issued`
- [ ] processHeartbeat() → событие в `device.events` при смене статуса
- [ ] Kafka down → confirm() проходит успешно (WARN в лог)
- [ ] Интеграционные тесты проходят
- [ ] **Текущая система работает как раньше** — heartbeat, presign, confirm без изменений
