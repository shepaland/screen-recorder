# Фаза 7: SQLite offline-буфер

## Цель

Реализовать персистентное хранение непереданных данных (сегменты, фокус-интервалы, input-события, аудит-события) в SQLite, чтобы при потере связи или перезапуске агента данные не терялись и отправлялись при восстановлении.

## Контекст проблемы

Текущая реализация хранит pending-данные в оперативной памяти:
- `UploadQueue` — очередь сегментов (actor `UploadQueueActor`, массив `[SegmentUploadItem]`)
- `ActivitySink` — `pendingIntervals`, `pendingInputBatches`, `pendingAuditEvents`

**Сценарии потери данных:**
1. Агент перезапускается (RESTART_AGENT, crash, обновление macOS) — все pending теряются
2. Длительный offline (>часа) — память растёт, при crash всё пропадает
3. KeepAlive перезапуск LaunchAgent — новый процесс не знает о старых данных

## Зависимости

- Фаза 3 (загрузка сегментов)
- Фаза 5 (трекинг активности)

## Задачи

1. Реализовать `OfflineStore` — SQLite-обёртка для CRUD операций
2. Создать схему таблиц для 4 типов данных
3. Интегрировать в `UploadQueue` — persist при enqueue, delete при успешной загрузке
4. Интегрировать в `ActivitySink` — persist при ошибке отправки, load при старте
5. Автоочистка: удалять записи старше 7 дней (expired TTL)
6. Восстановление при старте: загрузить pending данные из SQLite в очереди

## Архитектура

```
~/Library/Application Support/Kadero/offline.db

┌──────────────────────────────────────────────────────┐
│  SQLite (offline.db)                                 │
│                                                      │
│  pending_segments    — сегменты для загрузки          │
│  pending_focus       — фокус-интервалы               │
│  pending_input       — input event батчи             │
│  pending_audit       — аудит-события                 │
│                                                      │
│  Все таблицы: id, payload (JSON), created_ts,        │
│               retry_count, status                    │
└──────────────────────────────────────────────────────┘
```

## Схема SQLite

```sql
-- Единая структура для всех типов pending-данных
CREATE TABLE IF NOT EXISTS pending_items (
    id TEXT PRIMARY KEY,
    item_type TEXT NOT NULL,        -- 'segment', 'focus', 'input', 'audit'
    payload TEXT NOT NULL,          -- JSON-сериализованные данные
    session_id TEXT,                -- привязка к сессии записи (может быть NULL)
    created_ts TEXT NOT NULL,       -- ISO 8601
    retry_count INTEGER DEFAULT 0,
    max_retries INTEGER DEFAULT 10,
    status TEXT DEFAULT 'pending',  -- 'pending', 'in_progress', 'failed', 'expired'
    last_error TEXT,
    expires_at TEXT NOT NULL        -- created_ts + 7 days
);

CREATE INDEX IF NOT EXISTS idx_pending_type_status ON pending_items(item_type, status);
CREATE INDEX IF NOT EXISTS idx_pending_expires ON pending_items(expires_at);
```

## OfflineStore API

```swift
public final class OfflineStore {
    /// Открыть/создать БД по указанному пути
    init(path: String)  // ~/Library/Application Support/Kadero/offline.db

    /// Сохранить элемент в очередь
    func enqueue(id: String, type: ItemType, payload: Data, sessionId: String?) throws

    /// Получить N oldest pending-элементов указанного типа
    func dequeue(type: ItemType, limit: Int) throws -> [PendingItem]

    /// Пометить как in_progress (атомарно)
    func markInProgress(id: String) throws

    /// Удалить после успешной отправки
    func markCompleted(id: String) throws

    /// Увеличить retry_count, записать ошибку; если retry_count >= max → status='failed'
    func markRetry(id: String, error: String) throws

    /// Количество pending по типу
    func pendingCount(type: ItemType) -> Int

    /// Удалить expired (older than 7 days) и failed
    func cleanup() throws -> Int

    enum ItemType: String {
        case segment, focus, input, audit
    }
}
```

## Интеграция

### UploadQueue → OfflineStore

```
enqueue(segment):
  1. Сохранить в SQLite (type=segment, payload=JSON{fileURL, sessionId, sequenceNum, ...})
  2. Добавить в in-memory очередь для немедленной обработки

upload success:
  1. offlineStore.markCompleted(id)
  2. Удалить локальный файл

upload fail (retryable):
  1. offlineStore.markRetry(id, error)
  2. Элемент остаётся в SQLite для следующего цикла

При старте агента:
  1. offlineStore.dequeue(type: .segment, limit: 100)
  2. Для каждого: проверить fileURL существует → enqueue в UploadQueue
  3. Если файл не существует → markCompleted (orphan cleanup)
```

### ActivitySink → OfflineStore

```
sendFocusIntervals fail:
  1. Для каждого интервала: offlineStore.enqueue(type: .focus, payload: JSON)
  2. Убрать из pendingIntervals (теперь в SQLite)

sendFocusIntervals (батч-цикл):
  1. Загрузить из SQLite: offlineStore.dequeue(type: .focus, limit: 200)
  2. Объединить с in-memory pending
  3. Отправить батч
  4. При успехе: markCompleted для каждого
  5. При ошибке: markRetry

Аналогично для input и audit.
```

### Автоочистка

```
Каждые 5 минут (в pollLoop или отдельном таймере):
  1. offlineStore.cleanup() → DELETE WHERE expires_at < NOW() OR status = 'failed'
  2. Логировать количество удалённых
```

## macOS-специфика

- **SQLite**: системная библиотека, не требует внешних зависимостей. `import SQLite3` (C API)
- **Путь БД**: `~/Library/Application Support/Kadero/offline.db`
- **Concurrency**: SQLite в WAL mode (`PRAGMA journal_mode=WAL`) для concurrent reads
- **Thread safety**: один `sqlite3*` connection, все операции через serial DispatchQueue
- **Размер**: типичный объём pending данных — десятки MB, SQLite справляется легко

## Обработка ошибок

| Сценарий | Реакция |
|----------|---------|
| SQLite не открывается | Логировать, работать без offline (in-memory fallback) |
| Диск заполнен | Логировать, cleanup aggressive (удалить failed + oldest pending) |
| Corrupted DB | Удалить файл, создать заново |
| Pending > 10000 записей | Cleanup: удалить oldest 50% |
| retry_count >= max_retries | status='failed', не ретраить |

## Критерии приёмки

1. При потере связи данные сохраняются в SQLite, при восстановлении — отправляются
2. При перезапуске агента (kill -9, RESTART_AGENT) — pending данные восстанавливаются из SQLite
3. Сегменты: файл + метаданные сохраняются; при восстановлении upload продолжается
4. Focus/input/audit: JSON payload сохраняется, retry_count инкрементируется
5. Автоочистка: записи старше 7 дней удаляются автоматически
6. Failed записи (retry_count >= max) не ретраятся бесконечно
7. WAL mode: нет блокировок при concurrent read/write
8. Graceful fallback: если SQLite недоступен — агент работает как раньше (in-memory)
