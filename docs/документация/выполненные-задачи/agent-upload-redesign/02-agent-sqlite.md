# Фаза 2: Агент — SQLite v2 (новые таблицы + миграция)

> **Цель:** Подготовить локальное хранилище для новой state machine. Без изменений в сетевой логике — данные продолжают отправляться по-старому.
>
> **Зависит от:** ничего (параллельно с Фазой 1)
> **Блокирует:** Фазу 3 (Collector refactor)

---

## 2.1 SQLite init: auto_vacuum + WAL

**Файл:** `Storage/LocalDatabase.cs` — метод инициализации

**Изменение:** При создании/открытии БД выполнить PRAGMAs:

```csharp
// ВАЖНО: auto_vacuum должен быть ПЕРЕД созданием таблиц
// Если БД уже существует — auto_vacuum не может быть изменён без VACUUM
ExecuteNonQuery("PRAGMA auto_vacuum = INCREMENTAL");
ExecuteNonQuery("PRAGMA journal_mode = WAL");
```

> Если БД уже существует с `auto_vacuum=NONE`, нужно:
> 1. `PRAGMA auto_vacuum = INCREMENTAL`
> 2. `VACUUM` (однократно, при миграции)
> Это перестроит файл с поддержкой incremental vacuum.

**Проверка:**
- [ ] Новая БД: `PRAGMA auto_vacuum` → `2` (INCREMENTAL)
- [ ] Существующая БД: после миграции `PRAGMA auto_vacuum` → `2`
- [ ] `PRAGMA journal_mode` → `wal`

---

## 2.2 Новая таблица `pending_activity`

**Файл:** `Storage/LocalDatabase.cs` — метод `InitializeDatabase()`

```sql
CREATE TABLE IF NOT EXISTS pending_activity (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    event_id        TEXT    NOT NULL UNIQUE,
    data_type       TEXT    NOT NULL,
    session_id      TEXT,
    payload         TEXT    NOT NULL,
    status          TEXT    NOT NULL DEFAULT 'NEW',
    batch_id        TEXT,
    retry_count     INTEGER NOT NULL DEFAULT 0,
    last_error      TEXT,
    created_ts      TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
    updated_ts      TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);

CREATE INDEX IF NOT EXISTS idx_activity_status_type
  ON pending_activity(status, data_type);

CREATE INDEX IF NOT EXISTS idx_activity_batch
  ON pending_activity(batch_id);
```

**Валидные значения `data_type`:**
`FOCUS_INTERVAL`, `MOUSE_CLICK`, `KEYBOARD_METRIC`, `SCROLL`, `CLIPBOARD`, `AUDIT_EVENT`

**Валидные значения `status`:**
`NEW`, `QUEUED`, `SENDED`, `SERVER_SIDE_DONE`, `PENDING`

**Проверка:**
- [ ] Таблица создаётся при первом запуске
- [ ] Индексы создаются
- [ ] INSERT с валидными данными работает
- [ ] UNIQUE constraint на `event_id` работает (дубликат → ошибка)

---

## 2.3 Миграция `pending_segments`: добавить `status`

**Файл:** `Storage/LocalDatabase.cs`

**Текущая схема:**
```sql
-- Старая
CREATE TABLE pending_segments (
    id, file_path, session_id, sequence_num, size_bytes, created_ts, uploaded
);
```

**Новая схема:**
```sql
CREATE TABLE pending_segments (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    file_path       TEXT    NOT NULL,
    session_id      TEXT    NOT NULL,
    sequence_num    INTEGER NOT NULL,
    size_bytes      INTEGER NOT NULL,
    checksum_sha256 TEXT,
    duration_ms     INTEGER,
    recorded_at     TEXT,
    status          TEXT    NOT NULL DEFAULT 'NEW',
    segment_id      TEXT,
    retry_count     INTEGER NOT NULL DEFAULT 0,
    last_error      TEXT,
    created_ts      TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
    updated_ts      TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
    UNIQUE(session_id, sequence_num)
);

CREATE INDEX IF NOT EXISTS idx_segments_status ON pending_segments(status);
```

**Алгоритм миграции:**

```csharp
private void MigratePendingSegments()
{
    // Проверить существует ли колонка 'uploaded' (старый формат)
    var columns = GetColumnNames("pending_segments");
    if (!columns.Contains("uploaded")) return; // уже мигрирована или новая

    // 1. Переименовать старую таблицу
    ExecuteNonQuery("ALTER TABLE pending_segments RENAME TO pending_segments_old");

    // 2. Создать новую таблицу (см. DDL выше)
    CreatePendingSegmentsTable();

    // 3. Перенести данные
    ExecuteNonQuery(@"
        INSERT INTO pending_segments (id, file_path, session_id, sequence_num, size_bytes, created_ts, status)
        SELECT id, file_path, session_id, sequence_num, size_bytes, created_ts,
            CASE WHEN uploaded = 1 THEN 'SERVER_SIDE_DONE' ELSE 'PENDING' END
        FROM pending_segments_old
    ");

    // 4. Удалить старую таблицу
    ExecuteNonQuery("DROP TABLE pending_segments_old");
}
```

**Проверка:**
- [ ] Старая БД с `uploaded=0` записями → мигрируются в `status=PENDING`
- [ ] Старая БД с `uploaded=1` записями → мигрируются в `status=SERVER_SIDE_DONE`
- [ ] Новая БД (без старой таблицы) → создаётся новая таблица напрямую
- [ ] ID, file_path, session_id сохраняются

---

## 2.4 Миграция `audit_events` → `pending_activity`

**Файл:** `Storage/LocalDatabase.cs`

**Алгоритм:**

```csharp
private void MigrateAuditEvents()
{
    if (!TableExists("audit_events")) return;

    // Перенести неотправленные аудит-события в pending_activity
    ExecuteNonQuery(@"
        INSERT INTO pending_activity (event_id, data_type, session_id, payload, status, created_ts)
        SELECT
            id,
            'AUDIT_EVENT',
            session_id,
            json_object('event_type', event_type, 'event_ts', event_ts, 'details', details),
            CASE WHEN uploaded = 1 THEN 'SERVER_SIDE_DONE' ELSE 'PENDING' END,
            event_ts
        FROM audit_events
        WHERE uploaded = 0
    ");

    // Удалить старую таблицу
    ExecuteNonQuery("DROP TABLE audit_events");
}
```

**Проверка:**
- [ ] Неотправленные audit events мигрируются в `pending_activity` со статусом `PENDING`
- [ ] `data_type = 'AUDIT_EVENT'`
- [ ] payload содержит валидный JSON
- [ ] Старая таблица удаляется

---

## 2.5 Новые методы `LocalDatabase`

**Файл:** `Storage/LocalDatabase.cs`

### Для `pending_activity`:

```csharp
// INSERT новой activity-записи
public void InsertActivity(string eventId, string dataType, string? sessionId, string payload)

// SELECT записей для отправки (по типу и статусу)
public List<PendingActivity> GetPendingActivity(string dataType, int limit = 100)
public List<PendingActivity> GetPendingActivity(string[] dataTypes, int limit = 500)

// Bulk UPDATE статуса по списку ID
public void UpdateActivityStatus(List<int> ids, string status, string? batchId = null)

// UPDATE статуса одной записи с ошибкой
public void SetActivityError(int id, string error)

// SELECT количество записей по статусу (для метрик)
public Dictionary<string, int> GetActivityCountsByStatus()

// DELETE для cleanup
public int CleanupActivity(TimeSpan serverSideDoneAge, TimeSpan evictedAge, TimeSpan maxRetention)
```

### Для обновлённой `pending_segments`:

```csharp
// INSERT с новыми полями (status=NEW)
public void InsertSegment(string filePath, string sessionId, int sequenceNum,
                          long sizeBytes, string? recordedAt)

// SELECT для отправки
public PendingSegment? GetNextPendingSegment()

// UPDATE статуса
public void UpdateSegmentStatus(int id, string status, string? segmentId = null)

// UPDATE ошибки
public void SetSegmentError(int id, string error)

// SELECT количество по статусу (для метрик)
public Dictionary<string, int> GetSegmentCountsByStatus()

// SELECT для eviction (файлы со статусом для SegmentFileManager)
public List<PendingSegment> GetSegmentsForEviction()

// DELETE для cleanup
public int CleanupSegments(TimeSpan maxRetention)

// SELECT oldest unsent age
public int? GetOldestUnsentSegmentAgeSec()
public int? GetOldestUnsentActivityAgeSec()
```

### Модели данных:

```csharp
public class PendingActivity
{
    public int Id { get; set; }
    public string EventId { get; set; }
    public string DataType { get; set; }
    public string? SessionId { get; set; }
    public string Payload { get; set; }
    public string Status { get; set; }
    public string? BatchId { get; set; }
    public int RetryCount { get; set; }
    public string? LastError { get; set; }
    public DateTime CreatedTs { get; set; }
    public DateTime UpdatedTs { get; set; }
}

// PendingSegment — обновить существующую модель с новыми полями
```

**Проверка:**
- [ ] `InsertActivity` — запись появляется с `status=NEW`
- [ ] `GetPendingActivity("FOCUS_INTERVAL", 100)` — возвращает до 100 записей с `status IN ('NEW', 'PENDING')`
- [ ] `UpdateActivityStatus([1,2,3], "QUEUED")` — обновляет все 3 записи
- [ ] `CleanupActivity(1h, 24h, 72h)` — удаляет правильные записи
- [ ] `GetNextPendingSegment()` — возвращает 1 сегмент с `status IN ('NEW', 'PENDING')` ORDER BY id ASC
- [ ] `PRAGMA incremental_vacuum` вызывается в cleanup

---

## 2.6 Порядок выполнения при запуске

```csharp
public void InitializeDatabase()
{
    // 1. Открыть/создать SQLite
    OpenConnection();

    // 2. PRAGMAs (до создания таблиц!)
    ExecuteNonQuery("PRAGMA auto_vacuum = INCREMENTAL");
    ExecuteNonQuery("PRAGMA journal_mode = WAL");

    // 3. Создать новые таблицы (IF NOT EXISTS)
    CreatePendingSegmentsTable();
    CreatePendingActivityTable();
    CreateAgentStateTable();

    // 4. Мигрировать старые данные
    MigratePendingSegments();  // uploaded → status
    MigrateAuditEvents();      // audit_events → pending_activity

    // 5. Однократный VACUUM (если auto_vacuum только что включён)
    if (NeedsInitialVacuum())
    {
        ExecuteNonQuery("VACUUM");
    }
}
```

---

## Чеклист фазы 2

- [ ] 2.1 auto_vacuum=INCREMENTAL + WAL
- [ ] 2.2 Таблица `pending_activity` создаётся
- [ ] 2.3 `pending_segments` мигрирована (uploaded → status)
- [ ] 2.4 `audit_events` мигрирована в `pending_activity`
- [ ] 2.5 Все новые методы LocalDatabase реализованы
- [ ] 2.6 Порядок init корректный
- [ ] Агент компилируется
- [ ] Агент запускается с чистой БД
- [ ] Агент запускается с существующей БД (миграция проходит)
- [ ] **Старая сетевая логика работает без изменений** (UploadQueue, Sinks — всё по-прежнему)
