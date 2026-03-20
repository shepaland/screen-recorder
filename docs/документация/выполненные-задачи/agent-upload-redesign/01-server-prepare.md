# Фаза 1: Сервер — подготовка (backward-compatible)

> **Цель:** Подготовить серверную часть к новой схеме работы агента. Все изменения backward-compatible — старые агенты v1 продолжают работать без изменений.
>
> **Зависит от:** ничего
> **Блокирует:** Фазу 6 (тестирование)

---

## 1.1 Confirm: всегда sync + убрать S3 HEAD

**Файлы:**
- `ingest-gateway/src/.../service/IngestService.java`
- `ingest-gateway/src/.../controller/IngestController.java`

**Изменения:**

1. Убрать `kafka-only` mode из `confirm()`:
   - `confirmViaKafka()` → удалить
   - Всегда использовать `confirmSync()` с DB write
   - Env `KAFKA_CONFIRM_MODE` — игнорировать (или убрать)

2. Убрать S3 HEAD check из `confirmSync()`:
   - Сейчас: `s3Service.objectExists()` + size tolerance check — 20-100ms
   - Убрать этот вызов из sync-пути
   - Перенести в фоновый batch: `SegmentWriterConsumer` делает async S3 validation (уже есть)

3. Confirm response всегда `200 OK` (не `202`):
   ```json
   { "segment_id": "...", "status": "confirmed", "session_stats": {...} }
   ```

4. Kafka dual-write **оставить** (fire-and-forget для search-service, webhooks)

**Backward compatibility:** старые агенты уже ожидают `200 OK` от sync confirm. Единственное изменение — если на test стоит `kafka-only`, переключить на `sync`. Агенты не заметят разницы.

**Проверка:**
- [ ] Старый агент отправляет presign → PUT → confirm → получает 200 OK
- [ ] `SegmentWriterConsumer` продолжает получать события из Kafka
- [ ] search-service индексирует сегменты

---

## 1.2 Idempotent confirm

**Файл:** `ingest-gateway/src/.../service/IngestService.java`

**Изменение:** Перед `confirmSync()` проверить, не подтверждён ли сегмент уже:

```java
public ConfirmResponse confirm(ConfirmRequest request, DevicePrincipal principal) {
    Segment segment = segmentRepository.findByIdAndTenantId(request.getSegmentId(), principal.getTenantId())
        .orElseThrow(() -> new NotFoundException("SEGMENT_NOT_FOUND"));

    // Idempotent: уже confirmed → вернуть ACK без повторной записи
    if ("confirmed".equals(segment.getStatus())) {
        var stats = getSessionStats(segment.getSessionId());
        return new ConfirmResponse(segment.getId(), "confirmed", stats);
    }

    // ... остальная логика confirmSync
}
```

**Зачем:** Новый агент может повторить confirm (timeout → PENDING → retry). Без idempotency это создаст ошибку или дубликат.

**Backward compatibility:** Полная. Старый агент не отправляет повторные confirm.

**Проверка:**
- [ ] Повторный POST /confirm с тем же segment_id → 200 OK (не ошибка)
- [ ] session_stats корректны (не удваиваются)

---

## 1.3 Heartbeat: добавить `upload_enabled`

**Файлы:**
- `control-plane/src/.../dto/response/HeartbeatResponse.java`
- `control-plane/src/.../service/DeviceService.java`

**Изменения:**

1. Добавить поле в `HeartbeatResponse`:
   ```java
   private Boolean uploadEnabled = true;  // default true
   ```

2. Логика в `DeviceService.processHeartbeat()`:
   ```java
   boolean uploadEnabled = true;

   // Throttle check: HikariCP pool utilization
   // (опционально, можно добавить позже — пока всегда true)

   response.setUploadEnabled(uploadEnabled);
   ```

**Backward compatibility:** Полная. Старый агент игнорирует неизвестные поля в JSON (Jackson `FAIL_ON_UNKNOWN_PROPERTIES = false`). Новый агент читает `upload_enabled`.

**Проверка:**
- [ ] Heartbeat response содержит `"upload_enabled": true`
- [ ] Старый агент парсит heartbeat без ошибок (игнорирует поле)

---

## 1.4 Серверный throttling

**Файлы:**
- `control-plane/src/.../service/DeviceService.java`
- `control-plane/src/.../config/ThrottleConfig.java` (новый)

**Изменения:**

Простая реализация (v1 — можно усложнить позже):

```java
@Configuration
public class ThrottleConfig {
    @Value("${throttle.maintenance-mode:false}")
    private boolean maintenanceMode;

    public boolean isUploadEnabled() {
        if (maintenanceMode) return false;
        return true;
    }
}
```

Env: `THROTTLE_MAINTENANCE_MODE=false` (default). При необходимости включить через k8s env — `upload_enabled=false` для всех агентов.

HikariCP и MinIO проверки — добавить в будущем итеративно, не блокируют эту фазу.

**Проверка:**
- [ ] `THROTTLE_MAINTENANCE_MODE=true` → heartbeat возвращает `upload_enabled: false`
- [ ] `THROTTLE_MAINTENANCE_MODE=false` → heartbeat возвращает `upload_enabled: true`

---

## 1.5 Clipboard `content_hash` — миграция + DTO + индекс

**Файлы:**
- `ingest-gateway/src/main/resources/db/migration/V40__clipboard_content_hash.sql` (новый)
- `ingest-gateway/src/.../dto/request/ClipboardEvent.java`
- `ingest-gateway/src/.../entity/UserInputEvent.java`
- `ingest-gateway/src/.../service/InputEventService.java`

### 1.5.1 Flyway миграция

```sql
-- V40__clipboard_content_hash.sql
ALTER TABLE user_input_events ADD COLUMN content_hash TEXT;

CREATE INDEX idx_input_events_clipboard_hash
  ON user_input_events (tenant_id, content_hash)
  WHERE event_type = 'clipboard' AND content_hash IS NOT NULL;
```

### 1.5.2 DTO

```java
// ClipboardEvent.java — добавить поле
private String contentHash;  // SHA-256, optional
```

### 1.5.3 Entity

```java
// UserInputEvent.java — добавить колонку
@Column(name = "content_hash")
private String contentHash;
```

### 1.5.4 Service

```java
// InputEventService.java — при сохранении clipboard event
if (clipboardEvent.getContentHash() != null) {
    entity.setContentHash(clipboardEvent.getContentHash());
}
```

**Backward compatibility:** Полная. `content_hash` nullable. Старый агент не отправляет это поле — сохраняется как NULL. Новый агент отправляет — сохраняется.

**Проверка:**
- [ ] Flyway миграция V40 проходит без ошибок
- [ ] Старый агент: clipboard events сохраняются (content_hash = NULL)
- [ ] Новый агент: clipboard events с content_hash сохраняются корректно
- [ ] `SELECT * FROM user_input_events WHERE content_hash = 'abc...'` — использует индекс (EXPLAIN ANALYZE)

---

## 1.6 Activity endpoints: enriched response

**Файлы:**
- `ingest-gateway/src/.../controller/FocusIntervalController.java`
- `ingest-gateway/src/.../controller/InputEventController.java`
- `ingest-gateway/src/.../controller/AuditEventController.java`
- `ingest-gateway/src/.../service/FocusIntervalService.java`
- `ingest-gateway/src/.../service/InputEventService.java`
- `ingest-gateway/src/.../service/AuditEventService.java`

**Изменение:** POST endpoints возвращают `accepted/duplicates/total` вместо пустого тела:

```json
{
  "accepted": 87,
  "duplicates": 13,
  "total": 100
}
```

**Backward compatibility:** Полная. Старый агент проверяет только HTTP status code (200 OK), не парсит тело response.

**Проверка:**
- [ ] POST /focus-intervals → 200 + body с accepted/duplicates/total
- [ ] POST /input-events → 200 + body с accepted по типам
- [ ] POST /audit-events → 200 + body с accepted/duplicates/total
- [ ] Старый агент работает без ошибок

---

## Чеклист фазы 1

- [ ] 1.1 Confirm: sync only, no S3 HEAD
- [ ] 1.2 Idempotent confirm
- [ ] 1.3 HeartbeatResponse: `upload_enabled`
- [ ] 1.4 ThrottleConfig: maintenance mode
- [ ] 1.5 V40 migration: `content_hash` + partial index
- [ ] 1.6 Activity endpoints: enriched response
- [ ] Все существующие тесты проходят
- [ ] Старый агент работает без изменений
- [ ] Деплой на test-стейджинг
