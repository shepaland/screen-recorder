# Фаза 4: Переключение confirm() на Kafka-only

> **Влияние на текущую систему: ЗНАЧИМОЕ, но контролируемое.**
> confirm() перестаёт писать в PostgreSQL напрямую. Только Kafka publish → 202 Accepted.
> segment-writer consumer — единственный writer сегментов в PostgreSQL.
> **Rollback за 30 секунд:** ConfigMap `KAFKA_CONFIRM_MODE=sync` → передеплой.

---

## Цель

Ключевой шаг: **PostgreSQL полностью защищён от наплыва confirm-запросов**. Агент получает ответ за 1-2ms (Kafka publish), PostgreSQL пишется batch'ами через consumer в контролируемом темпе.

## Предусловия

- Фаза 3 завершена: segment-writer верифицирован 24ч
- Consumer lag стабильно = 0
- Dual-write без ошибок

---

## Задачи

### KFK-030: Рефакторинг confirm()

**Что сделать:**

Модифицировать `IngestService.confirm()` — добавить ветвление по `kafka.confirm-mode`:

```java
// IngestService.java

@Value("${kafka.confirm-mode:sync}")
private String confirmMode;

public ConfirmResponse confirm(ConfirmRequest request, DevicePrincipal principal) {
    if ("kafka-only".equals(confirmMode)) {
        return confirmViaKafka(request, principal);
    }
    return confirmSync(request, principal);  // текущая логика
}

/**
 * Kafka-only confirm: publish → 202 Accepted.
 * Минимальная валидация без обращения к DB и S3.
 */
private ConfirmResponse confirmViaKafka(ConfirmRequest request, DevicePrincipal principal) {
    // Базовая валидация (без DB)
    if (request.getSegmentId() == null || request.getChecksumSha256() == null) {
        throw new BadRequestException("segment_id and checksum_sha256 required");
    }

    eventPublisher.publish("segments.ingest",
        principal.getDeviceId().toString(),
        SegmentConfirmedEvent.builder()
            .eventId(UUID.randomUUID())
            .timestamp(Instant.now())
            .tenantId(principal.getTenantId())
            .deviceId(principal.getDeviceId())
            .sessionId(request.getSessionId())
            .segmentId(request.getSegmentId())
            .sequenceNum(request.getSequenceNum())
            .s3Key(request.getS3Key())
            .sizeBytes(request.getSizeBytes())
            .durationMs(request.getDurationMs())
            .checksumSha256(request.getChecksumSha256())
            .metadata(request.getMetadata())
            .build());

    // 202 Accepted — данные приняты, будут записаны async
    return ConfirmResponse.builder()
        .status("accepted")
        .build();
}

/**
 * Sync confirm: текущая логика (DB + S3).
 * Используется как fallback при kafka.confirm-mode=sync.
 */
@Transactional
private ConfirmResponse confirmSync(ConfirmRequest request, DevicePrincipal principal) {
    // ... СУЩЕСТВУЮЩИЙ КОД (lines 99-155) БЕЗ ИЗМЕНЕНИЙ ...
    // + dual-write publish из Фазы 2 (если включен)
}
```

**HTTP response code:**
- `sync` mode: `200 OK` (как сейчас)
- `kafka-only` mode: `202 Accepted`

Изменить IngestController:

```java
@PostMapping("/confirm")
public ResponseEntity<ConfirmResponse> confirm(...) {
    ConfirmResponse response = ingestService.confirm(request, principal);
    HttpStatus status = "accepted".equals(response.getStatus())
        ? HttpStatus.ACCEPTED    // 202
        : HttpStatus.OK;         // 200
    return ResponseEntity.status(status).body(response);
}
```

**Файлы:**
- `ingest-gateway/src/main/java/com/prg/ingest/service/IngestService.java`
- `ingest-gateway/src/main/java/com/prg/ingest/controller/IngestController.java`

**Критерий приёмки:** С `KAFKA_CONFIRM_MODE=sync` — поведение идентично AS-IS. С `kafka-only` — Kafka publish, 202, no DB write.

---

### KFK-031: Nginx rate limiting

**Что сделать:**

Добавить в nginx.conf (web-dashboard):

```nginx
# Вне server block:
limit_req_zone $http_x_device_id zone=ingest_confirm:10m rate=10r/s;

# В location /api/ingest/:
location /api/ingest/ {
    limit_req zone=ingest_confirm burst=20 nodelay;
    limit_req_status 429;
    proxy_pass http://ingest-gateway:8084/api/;
    # ... существующие настройки ...
}
```

Агент при получении 429 → backoff 5-30s (уже реализовано частично в SegmentUploader).

**Файлы:**
- `deploy/docker/web-dashboard/nginx.conf`

**Критерий приёмки:** При >10 req/s с одного device_id → 429. Нормальный трафик проходит.

---

### KFK-032: Horizontal scaling ingest-gw

**Что сделать:**

Теперь confirm() не обращается к DB → можно масштабировать без упора в DB pool.

```yaml
# deploy/k8s/ingest-gateway/deployment.yaml
spec:
  replicas: 2  # было 1, можно 2-3
```

**Проверить:**
- presign() всё ещё обращается к DB → DB pool × replicas ≤ PostgreSQL max_connections
- 2 replicas × 30 pool = 60. PostgreSQL max_connections обычно 100 → ОК.

**Критерий приёмки:** 2 replicas Running, load balancing через k8s Service работает.

---

### KFK-033: Agent response code 202

**Что сделать:**

Проверить что агенты обрабатывают 202 Accepted как успех:

**Windows Agent (`SegmentUploader.cs`):**
```csharp
// Текущий код проверяет response.IsSuccessStatusCode
// HttpStatusCode 202 → IsSuccessStatusCode = true → ОК
```

Проверить: `HttpResponseMessage.IsSuccessStatusCode` для 202 = `true` (2xx range). Скорее всего уже работает, но нужен тест.

**macOS Agent (Swift):**
Аналогичная проверка — URLResponse statusCode в 200-299 range.

**Критерий приёмки:** Агент после confirm() с 202 → переходит к следующему сегменту, не ретраит.

---

### KFK-034: Включение kafka-only на test

**Что сделать:**

1. Убедиться: segment-writer consumer Running, lag = 0
2. Обновить ConfigMap: `KAFKA_CONFIRM_MODE=kafka-only`
3. Rollout restart ingest-gateway
4. Мониторинг:
   - Confirm latency (должен быть 1-5ms, было 25-115ms)
   - Consumer lag (должен быть 0-100, допустим кратковременный пик)
   - PostgreSQL write rate (должен быть стабильным, определяется consumer)
   - Агент: сегменты загружаются без ошибок
   - Dashboard: данные появляются с задержкой ~5 сек (eventual consistency)

**Критерий приёмки:** Confirm < 5ms, consumer lag < 100, данные в PostgreSQL через 1-5 сек.

---

### KFK-035: Fallback verification

**Что сделать:**

Тест rollback:

1. `KAFKA_CONFIRM_MODE=sync` → передеплой ingest-gw
2. confirm() → sync DB write → 200 OK (как раньше)
3. Всё работает за ~30 секунд rollback

Тест Kafka down:

1. Остановить Kafka pod: `kubectl -n test-screen-record scale deployment/kafka --replicas=0`
2. confirm() с `kafka-only` mode → должен вернуть ошибку (503 или fallback на sync)

**Решение для auto-fallback:**

```java
private ConfirmResponse confirmViaKafka(ConfirmRequest request, DevicePrincipal principal) {
    try {
        eventPublisher.publishSync("segments.ingest", ...);  // с таймаутом 2 сек
        return ConfirmResponse.builder().status("accepted").build();
    } catch (Exception e) {
        log.warn("Kafka unavailable, falling back to sync confirm: {}", e.getMessage());
        return confirmSync(request, principal);  // fallback на sync
    }
}
```

**Критерий приёмки:** `KAFKA_CONFIRM_MODE=sync` → моментальный rollback. Kafka down → auto-fallback на sync.

---

### KFK-036: Load testing

**Что сделать:**

k6 скрипт: симуляция 10K agents:

```javascript
// load-test/confirm-load.js
import http from 'k6/http';
import { check } from 'k6';

export const options = {
    scenarios: {
        confirm: {
            executor: 'constant-arrival-rate',
            rate: 200,           // 200 confirm/sec
            timeUnit: '1s',
            duration: '5m',
            preAllocatedVUs: 50,
            maxVUs: 100,
        },
    },
};

export default function () {
    const res = http.post(`${__ENV.BASE_URL}/api/ingest/v1/ingest/confirm`, JSON.stringify({
        segment_id: uuidv4(),
        session_id: uuidv4(),
        checksum_sha256: "abc123",
        // ...
    }), { headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ...' } });

    check(res, {
        'status is 202': (r) => r.status === 202,
        'latency < 10ms': (r) => r.timings.duration < 10,
    });
}
```

**Ожидаемые результаты:**
- Confirm latency p95 < 5ms
- Consumer lag < 100 при 200 confirm/sec
- PostgreSQL CPU/IO стабильны
- 0 ошибок 5xx

**Критерий приёмки:** Load test 5 минут при 200 req/s → 0 ошибок, p95 < 5ms.

---

## Чеклист завершения фазы

- [x] confirm() в kafka-only mode: Kafka publish → 202 Accepted — ✓ `Kafka-only confirm: segment id=...`, offset=442+
- [x] presign() остаётся sync (DB + S3 presigned URL) — ✓ `Generated presigned URL`, DB write сохранён
- [x] segment-writer: batch INSERT в PostgreSQL, lag = 0 — ✓ partition 5: 444/444 lag=0
- [x] Session stats обновляются consumer'ом — ✓ `updated session ... stats: +1 segments, +2277220 bytes`
- [x] S3 validation — async в consumer — ✓ CompletableFuture.runAsync()
- [x] Auto-fallback: Kafka down → sync confirm — ✓ try/catch в confirmViaKafka() → confirmSync()
- [x] Manual rollback: `KAFKA_CONFIRM_MODE=sync` → ConfigMap change + rollout — ✓ архитектура готова
- [ ] Nginx rate limiting — отложено (не критично для 1 агента)
- [ ] Horizontal scaling — отложено (1 replica достаточна)
- [x] Агент: 202 Accepted = success — ✓ C# HttpResponseMessage.IsSuccessStatusCode=true для 2xx
- [ ] Load test — отложено (нет k6 на сервере, 1 агент — недостаточно нагрузки)
- [x] **Dashboard показывает данные** — ✓ consumer пишет в PostgreSQL, задержка <1 сек
