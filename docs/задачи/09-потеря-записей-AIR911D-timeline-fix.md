# Аналитика: корректное размещение недозагруженных сегментов на таймлайне

## Проблема

Pending-сегменты (34-44), записанные в 10:30–10:52 MSK, после восстановления попали в сессию `2a0a920b` (started 12:58 MSK) и отображаются на таймлайне после 12:00 вместо правильного интервала 10:30–10:52.

## Как устроен таймлайн сейчас

```
Позиция сегмента = session.started_ts + sequence_num × duration_ms
```

| Компонент | Что определяет позицию | Файл |
|-----------|----------------------|------|
| DayTimeline.tsx | `session.started_ts` → `tsToMinutes()` → блок на шкале 0–1440 | web-dashboard |
| RecordingService.java | `findSessionsByDeviceIdAndDate()` по `DATE(started_ts AT TIME ZONE tz)` | ingest-gateway |
| PlaybackService.java | Порядок в M3U8 → `sequence_num ASC` | playback-service |

**Критический факт:** у сегментов **нет собственной метки времени записи**. Есть только:
- `created_ts` — момент вызова presign на сервере (автоматически, `DEFAULT NOW()`)
- `sequence_num` — порядковый номер внутри сессии
- `duration_ms` — длительность (всегда 60000)

Время на таймлайне вычисляется косвенно: `session.started_ts + seq × 60s`.

## Почему сегменты "сдвинулись"

```
Оригинальная сессия 7f64ec83:
  started_ts = 10:07 MSK
  segment 34: позиция = 10:07 + 34×60s = 10:41 MSK ← ПРАВИЛЬНО

После recovery в сессию 2a0a920b:
  started_ts = 12:58 MSK
  segment 34: позиция = 12:58 + 34×60s = 13:32 MSK ← НЕПРАВИЛЬНО
```

## Варианты решения

### Вариант A: Разрешить загрузку в закрытые сессии (рекомендуемый)

**Суть:** Не переназначать pending-сегменты в новую сессию. Загружать в ОРИГИНАЛЬНУЮ сессию, даже если она `completed` или `interrupted`.

**Изменения:**

1. **ingest-gateway** — IngestService.presign(): убрать проверку `status = active` для presign. Разрешить загрузку в сессии со статусом `completed`/`interrupted`, если сегмент от того же device_id:

```java
// Сейчас (строгая проверка):
if (!"active".equals(session.getStatus())) {
    throw new ConflictException("Session is not active");
}

// После (мягкая проверка):
if ("active".equals(session.getStatus()) || isLateUpload(session, principal)) {
    // OK — разрешаем позднюю загрузку
} else {
    throw new ConflictException("Session is not active");
}

private boolean isLateUpload(RecordingSession session, DevicePrincipal principal) {
    // Разрешаем загрузку в completed/interrupted сессии того же устройства
    // в течение 24 часов после закрытия
    return principal.getDeviceId().equals(session.getDeviceId())
        && session.getEndedTs() != null
        && session.getEndedTs().isAfter(Instant.now().minus(Duration.ofHours(24)));
}
```

2. **Windows Agent** — UploadQueue.ProcessLoop(): вместо дискарда стейл-сегментов — загружать с оригинальным session_id:

```csharp
// Сейчас: дискардит сегменты из старых сессий
if (currentSession != null && segment.SessionId != currentSession)
{
    _logger.LogWarning("Discarding stale segment...");
    continue;
}

// После: загружает как есть с оригинальным session_id
// (сервер принимает late upload в completed/interrupted сессии)
```

3. **ingest-gateway** — при late upload обновлять `segment_count`/`total_bytes`/`total_duration_ms` сессии, но НЕ менять `ended_ts` и `status`.

**Плюсы:**
- Сегменты остаются в своей сессии → правильная позиция на таймлайне
- Минимальные изменения (только снятие 409 ограничения + логика late upload)
- Не нужна миграция БД

**Минусы:**
- Усложнение логики presign (два режима: active и late upload)
- `segment_count` в закрытой сессии может расти задним числом

**Объём работ:** ~2-3 часа

---

### Вариант B: Добавить `recorded_at` в presign-запрос

**Суть:** Агент передаёт метку времени создания файла (`File.GetCreationTimeUtc`) в presign-запросе. Сервер сохраняет в сегменте. Таймлайн использует `recorded_at` для позиционирования.

**Изменения:**

1. **Миграция БД** — новая колонка:
```sql
ALTER TABLE segments ADD COLUMN recorded_at TIMESTAMPTZ;
```

2. **Agent** — SegmentUploader: добавить `recorded_at` в presign body:
```csharp
var presignBody = new {
    // ... существующие поля ...
    recorded_at = File.GetCreationTimeUtc(filePath).ToString("o")
};
```

3. **ingest-gateway** — Segment entity + presign request DTO: принять и сохранить `recorded_at`.

4. **ingest-gateway** — RecordingService.getDeviceDayTimeline(): использовать `recorded_at` для позиционирования:
```java
// Timeline position = recorded_at (если есть), иначе session.started_ts + seq × duration
```

5. **web-dashboard** — DayTimeline.tsx: использовать `recorded_at` из сегмента вместо вычисленной позиции.

**Плюсы:**
- Точное время записи, не зависит от задержки загрузки
- Работает для любых edge-case (offline, retry, задержки сети)
- Полезно для аналитики (latency загрузки = `created_ts - recorded_at`)

**Минусы:**
- Миграция БД (новая колонка)
- Изменения во всех слоях: агент, сервер, фронтенд
- `recorded_at` есть только у новых сегментов; для старых — fallback на вычисление

**Объём работ:** ~4-6 часов

---

### Вариант C: Гибридный (A + B)

**Фаза 1 (сейчас):** Вариант A — разрешить late upload в закрытые сессии. Решает проблему без миграций.

**Фаза 2 (позже):** Вариант B — добавить `recorded_at` для точного таймлайна. Подстраховка на случай сложных сценариев (агент офлайн несколько часов, ротация сессий).

---

## Рекомендация

**Вариант A** — реализовать первым. Он решает 100% текущей проблемы:
- Pending-сегменты загружаются в свою оригинальную сессию
- Позиция на таймлайне автоматически правильная
- Минимум изменений, без миграций

Ключевые изменения (3 файла):

| Файл | Изменение |
|------|-----------|
| `IngestService.java` | Разрешить presign для completed/interrupted сессий (late upload, ≤24ч) |
| `UploadQueue.cs` | Не дискардить стейл-сегменты — загружать с оригинальным session_id |
| `SegmentWriterConsumer.java` | Обновлять stats закрытой сессии при late upload |

## Немедленный workaround (пока фикс не задеплоен)

Сегменты 34-44 можно переместить обратно в оригинальную сессию через SQL:

```sql
-- Переместить ошибочно назначенные сегменты обратно в 7f64ec83
-- (только те, чей sequence_num совпадает с пропущенным диапазоном 34-44)
UPDATE segments
SET session_id = '7f64ec83-167a-4c1c-b63d-346a82ba8fc8'
WHERE session_id = '2a0a920b-0ba3-4225-90dd-d72726be1074'
  AND sequence_num BETWEEN 34 AND 44;

-- Пересчитать stats для обеих сессий
UPDATE recording_sessions SET
  segment_count = (SELECT COUNT(*) FROM segments WHERE session_id = '7f64ec83-167a-4c1c-b63d-346a82ba8fc8' AND status = 'confirmed'),
  total_bytes = (SELECT COALESCE(SUM(size_bytes),0) FROM segments WHERE session_id = '7f64ec83-167a-4c1c-b63d-346a82ba8fc8' AND status = 'confirmed')
WHERE id = '7f64ec83-167a-4c1c-b63d-346a82ba8fc8';

UPDATE recording_sessions SET
  segment_count = (SELECT COUNT(*) FROM segments WHERE session_id = '2a0a920b-0ba3-4225-90dd-d72726be1074' AND status = 'confirmed'),
  total_bytes = (SELECT COALESCE(SUM(size_bytes),0) FROM segments WHERE session_id = '2a0a920b-0ba3-4225-90dd-d72726be1074' AND status = 'confirmed')
WHERE id = '2a0a920b-0ba3-4225-90dd-d72726be1074';
```
