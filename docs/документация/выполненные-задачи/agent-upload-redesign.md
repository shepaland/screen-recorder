# Системная аналитика: Редизайн схемы агент ↔ сервер

> **Дата:** 2026-03-18
> **Статус:** Планирование
> **Автор:** SA (Claude)
> **Версия:** 3.1
> **Изменения v2:** Расширение на все типы данных: видеосегменты + focus intervals + input events + audit events
> **Изменения v3:** Решения по открытым вопросам: отдельный upload-поток (не блокирует heartbeat), round-robin отправка, clipboard hash, auto_vacuum
> **Изменения v3.1:** Серверный throttling (upload_enabled), retention 72 часа, индекс content_hash для clipboard корреляции

---

## 0. Мотивация

Текущая архитектура агента совмещает запись и отправку в одном потоке управления: `WatchSegments` обнаруживает новый файл → `UploadQueue` сразу пытается отправить. Это создаёт проблемы:

1. **Связанность** — если сервер недоступен, upload-логика блокирует/замедляет pipeline записи.
2. **Неявные статусы** — сегмент помечен `uploaded=0/1`, нет промежуточных состояний для диагностики.
3. **Отсутствие серверного подтверждения** — в `kafka-only` режиме сервер возвращает 202 ("принял в очередь"), но агент считает это финальным успехом. Если Kafka consumer упал — сегмент потерян.
4. **Нет привязки к heartbeat** — upload работает постоянно, независимо от состояния связи с сервером.
5. **Потеря activity-данных при крашах** — focus intervals и input events хранятся только в памяти (`ConcurrentQueue`), при перезапуске агента теряются безвозвратно.
6. **Разрозненные sink'и** — три независимых `BackgroundService` (`AuditEventSink`, `FocusIntervalSink`, `InputEventSink`) с разными стратегиями persistence: audit — SQLite, focus/input — только RAM.

---

## 1. Целевая архитектура (TO-BE)

### 1.1 Четыре типа данных — единая схема синхронизации

Агент собирает и передаёт на сервер **4 типа данных**. Все должны проходить через единую state machine:

| # | Тип данных | Что содержит | Текущее хранение | Проблема |
|---|-----------|-------------|-----------------|----------|
| 1 | **Видеосегменты** | .mp4 файлы (fMP4, H.264) | SQLite `pending_segments` + файловая система | `uploaded=0/1` — нет промежуточных статусов |
| 2 | **Focus Intervals** | Какое приложение активно, домен сайта, размер окна, is_maximized, is_fullscreen, is_idle, monitor_index | **Только RAM** (`ConcurrentQueue`) | Теряются при крашах/перезапусках |
| 3 | **Input Events** | Mouse clicks (x,y,button,UI element), keyboard metrics (keystroke_count, has_typing_burst), scroll (direction, delta), clipboard | **Только RAM** (`ConcurrentQueue`) | Теряются при крашах/перезапусках |
| 4 | **Audit Events** | SESSION_LOCK/UNLOCK/LOGON/LOGOFF, PROCESS_START/STOP | SQLite `audit_events` | `uploaded=0/1` — нет промежуточных статусов |

**Сервер уже готов принимать все эти данные** через существующие endpoints:

| Тип | Endpoint | Метод | Batch limit |
|-----|----------|-------|-------------|
| Видеосегменты | `/api/v1/ingest/presign` + `/confirm` | POST + PUT + POST | 1 за раз |
| Focus Intervals | `/api/v1/ingest/activity/focus-intervals` | POST | 100 |
| Input Events | `/api/v1/ingest/activity/input-events` | POST | 500 (clicks), 100 (keyboard), 200 (scroll), 50 (clipboard) |
| Audit Events | `/api/v1/ingest/audit-events` | POST | 100 |

### 1.2 Три независимых потока

```
┌──────────────────────────────────────────────────────────────────────────┐
│                        Windows Agent (KaderoAgent)                        │
│                                                                           │
│  ┌──────────────────┐  ┌──────────────────┐  ┌────────────────────────┐ │
│  │ ПОТОК 1: Collector│  │ ПОТОК 2: Heartbeat│  │ ПОТОК 3: Uploader     │ │
│  │                  │  │  (30s цикл,      │  │  (непрерывный,        │ │
│  │ FFmpeg → .mp4    │  │   ВСЕГДА)        │  │   работает пока       │ │
│  │    ↓ INSERT NEW  │  │                  │  │   server_available)    │ │
│  │                  │  │ PUT /heartbeat   │  │                        │ │
│  │ Focus Intervals  │  │    ↓             │  │ Round-robin loop:      │ │
│  │    ↓ INSERT NEW  │  │ Обработать       │  │  1. batch activity     │ │
│  │                  │  │ commands/settings│  │  2. 1 video segment    │ │
│  │ Input Events     │  │    ↓             │  │  3. batch activity     │ │
│  │    ↓ INSERT NEW  │  │ Обновить флаг:   │  │  4. 1 video segment    │ │
│  │                  │  │ server_available │  │  ...                   │ │
│  │ Audit Events     │  │  = true/false    │  │                        │ │
│  │    ↓ INSERT NEW  │  │                  │  │ Если !server_available │ │
│  │                  │  │ await 30s        │  │  → sleep, ждать флаг   │ │
│  └──────────────────┘  └──────────────────┘  └────────────────────────┘ │
│          ↕                    ↕                       ↕                   │
│     ┌─────────────────────────────────────────────────────────┐         │
│     │                   agent.db (SQLite)                      │         │
│     │  pending_segments    — видеофайлы                        │         │
│     │  pending_activity    — focus + input + audit             │         │
│     │  Все: NEW → QUEUED → SENDED → SERVER_SIDE_DONE          │         │
│     └─────────────────────────────────────────────────────────┘         │
│                                  ↕                                       │
│     ┌─────────────────────────────────────────────────────────┐         │
│     │              server_available (volatile bool)            │         │
│     │  Heartbeat OK → true     Heartbeat fail → false         │         │
│     └─────────────────────────────────────────────────────────┘         │
└──────────────────────────────────────────────────────────────────────────┘
```

**Ключевое отличие от v2:** Uploader — это **отдельный поток**, который работает непрерывно и **не блокирует heartbeat**. Heartbeat всегда ходит раз в 30 секунд, а Uploader отправляет данные в своём темпе. Если отправка не успевает за 30s — не важно, heartbeat просто обновляет флаг `server_available`, а Uploader продолжает работать.

### 1.3 Жизненный цикл записи — Единая State Machine (все типы данных)

```
              Recorder                           Uploader
              ────────                           ────────
                 │                                  │
          FFmpeg создал файл                        │
                 │                                  │
                 ▼                                  │
            ┌────────┐                              │
            │  NEW   │ ◄── запись в SQLite           │
            └────┬───┘                              │
                 │                                  │
                 │         Heartbeat получен ────────┤
                 │                                  │
                 │                              ┌───▼────┐
                 │                              │ QUEUED │ ◄── Uploader взял в работу
                 │                              └───┬────┘
                 │                                  │
                 │                          Presign+PUT+Confirm
                 │                                  │
                 │                              ┌───▼────┐
                 │                              │ SENDED │ ◄── Confirm отправлен
                 │                              └───┬────┘
                 │                                  │
                 │                         ┌────────┴────────┐
                 │                         │                 │
                 │                    ACK получен       Timeout/Error
                 │                         │                 │
                 │                  ┌──────▼───────┐  ┌──────▼───┐
                 │                  │SERVER_SIDE_  │  │ PENDING  │
                 │                  │   DONE       │  │          │
                 │                  └──────┬───────┘  └──────┬───┘
                 │                         │                 │
                 │                  Удалить файл      Retry на след.
                 │                                    heartbeat
```

**Допустимые переходы:**
| Из | В | Кто | Условие |
|----|---|-----|---------|
| — | NEW | Recorder | Новый файл обнаружен на диске |
| NEW | QUEUED | Uploader | Heartbeat получен, Uploader начинает обработку |
| QUEUED | SENDED | Uploader | Presign + PUT + Confirm успешно отправлены |
| SENDED | SERVER_SIDE_DONE | Uploader | Сервер подтвердил сохранение |
| SENDED | PENDING | Uploader | Timeout ожидания ACK / ошибка сети |
| QUEUED | PENDING | Uploader | Ошибка на этапе presign/PUT |
| PENDING | QUEUED | Uploader | Следующий heartbeat → повторная попытка |

**Терминальное состояние:** `SERVER_SIDE_DONE` — файл можно удалять с диска (для сегментов), запись можно удалить из SQLite (для activity-данных).

### 1.4 Что собирает Collector (Процесс 1) — детали по каждому типу

#### 1.4.1 Видеосегменты

| Поле | Описание |
|------|----------|
| `file_path` | Путь к .mp4 файлу на диске |
| `session_id` | UUID записывающей сессии |
| `sequence_num` | Порядковый номер сегмента в сессии |
| `size_bytes` | Размер файла |
| `recorded_at` | Время создания файла (UTC) |

**Источник:** FFmpeg → `WatchSegments` обнаруживает новые .mp4 → INSERT в `pending_segments`.

**Отправка на сервер:** 3-step (presign → PUT S3 → confirm). Каждый сегмент — индивидуально.

#### 1.4.2 Focus Intervals (история приложений и сайтов)

| Поле | Описание | Пример |
|------|----------|--------|
| `process_name` | Имя процесса | `chrome.exe`, `1cv8.exe` |
| `window_title` | Заголовок окна | `GitHub — Mozilla Firefox` |
| `is_browser` | Это браузер? | `true` / `false` |
| `browser_name` | Название браузера | `Chrome`, `Edge`, `Firefox` |
| `domain` | Домен сайта (если браузер) | `github.com`, `mail.google.com` |
| `started_at` / `ended_at` | Время фокуса | ISO 8601 |
| `duration_ms` | Длительность | 15000 |
| `window_x/y/width/height` | Геометрия окна | координаты и размер |
| `is_maximized` | Развёрнуто на весь экран | `true` / `false` |
| `is_fullscreen` | Полноэкранный режим | `true` / `false` |
| `is_idle` | Нет активного ввода от пользователя | `true` (нет клавиатуры/мыши) |
| `monitor_index` | На каком мониторе | `0`, `1` |
| `session_id` | UUID записывающей сессии | привязка к видеозаписи |

**Источник:** `TrayWindowTracker` (poll каждые 5s) + `BrowserUrlExtractor` (UI Automation для URL) + `GetLastInputInfo` (idle detection).

**Текущая проблема:** данные хранятся в `ConcurrentQueue<FocusInterval>` в RAM. При крашах теряются.

**Новое:** INSERT в SQLite `pending_activity` со статусом `NEW` сразу при создании.

**Отправка на сервер:** batch POST `/api/v1/ingest/activity/focus-intervals` (до 100 за раз).

#### 1.4.3 Input Events (клики, клавиатура, скролл)

**Mouse Clicks:**
| Поле | Описание |
|------|----------|
| `x`, `y` | Координаты клика |
| `button` | `left` / `right` / `middle` |
| `click_type` | `single` / `double` |
| `ui_element_type` | Тип UI-элемента (`Button`, `Link`, `MenuItem`, `TabItem`) |
| `ui_element_name` | Имя элемента (Edit/Document → `[input]` для приватности) |
| `process_name` | В каком приложении |
| `segment_id` / `segment_offset_ms` | Привязка к моменту видеозаписи |

**Keyboard Metrics:**
| Поле | Описание |
|------|----------|
| `keystroke_count` | Кол-во нажатий за интервал (10 сек) |
| `has_typing_burst` | Активный ввод (≥10 клавиш за 5 сек) |
| `interval_start/end` | Временной интервал |
| `process_name` | В каком приложении |

**Scroll:**
| Поле | Описание |
|------|----------|
| `direction` | `up` / `down` / `left` / `right` |
| `total_delta` | Суммарная дельта скролла |
| `event_count` | Количество событий скролла за интервал |
| `process_name` | В каком приложении |

**Clipboard:**
| Поле | Описание |
|------|----------|
| `action` | `copy` / `cut` / `paste` |
| `source_process` | Из какого приложения |
| `content_type` | `text` / `image` / `files` / `other` |
| `content_length` | Размер содержимого в байтах |
| `content_hash` | **SHA-256 hash содержимого** — для корреляции copy↔paste между приложениями без хранения самого содержимого |

**Источник:** `InputTracker` в Tray-процессе (low-level hooks `WH_MOUSE_LL` + `WH_KEYBOARD_LL`). Передаётся в Service через Named Pipe каждые 30s.

**Текущая проблема:** данные в `ConcurrentQueue<InputEvent>` в RAM. Теряются при крашах.

**Новое:** INSERT в SQLite `pending_activity` со статусом `NEW` при получении через Named Pipe.

**Отправка на сервер:** batch POST `/api/v1/ingest/activity/input-events` (до 500 clicks + 100 keyboard + 200 scroll за раз).

#### 1.4.4 Audit Events (системные события)

| Тип | Описание |
|-----|----------|
| `SESSION_LOCK` | Пользователь заблокировал сессию |
| `SESSION_UNLOCK` | Пользователь разблокировал сессию |
| `SESSION_LOGON` | Вход пользователя |
| `SESSION_LOGOFF` | Выход пользователя |
| `PROCESS_START` | Запуск приложения (exe_path, pid) |
| `PROCESS_STOP` | Закрытие приложения |

**Источник:** `SessionWatcher` (SystemEvents.SessionSwitch) + `ProcessWatcher` (WMI). Уже сохраняются в SQLite `audit_events`.

**Текущая проблема:** `uploaded=0/1` без промежуточных статусов.

**Новое:** мигрировать на единую `pending_activity` таблицу с полным status enum.

**Отправка на сервер:** batch POST `/api/v1/ingest/audit-events` (до 100 за раз).

---

## 2. Изменения на стороне агента (C#)

### 2.1 Новые SQLite-таблицы

#### Таблица 1: `pending_segments` (видеосегменты)

```sql
CREATE TABLE IF NOT EXISTS pending_segments (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    file_path       TEXT    NOT NULL,
    session_id      TEXT    NOT NULL,
    sequence_num    INTEGER NOT NULL,
    size_bytes      INTEGER NOT NULL,
    checksum_sha256 TEXT,           -- вычисляется при QUEUED
    duration_ms     INTEGER,
    recorded_at     TEXT,           -- ISO 8601
    status          TEXT    NOT NULL DEFAULT 'NEW',
      -- NEW | QUEUED | SENDED | SERVER_SIDE_DONE | PENDING | EVICTED
    segment_id      TEXT,           -- UUID от сервера (после presign)
    retry_count     INTEGER NOT NULL DEFAULT 0,
    last_error      TEXT,           -- последняя ошибка
    created_ts      TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
    updated_ts      TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
    UNIQUE(session_id, sequence_num)
);

CREATE INDEX idx_segments_status ON pending_segments(status);
```

#### Таблица 2: `pending_activity` (focus intervals + input events + audit events)

```sql
CREATE TABLE IF NOT EXISTS pending_activity (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    event_id        TEXT    NOT NULL UNIQUE,    -- UUID, для дедупликации на сервере
    data_type       TEXT    NOT NULL,
      -- FOCUS_INTERVAL | MOUSE_CLICK | KEYBOARD_METRIC | SCROLL | CLIPBOARD | AUDIT_EVENT
    session_id      TEXT,                       -- UUID записывающей сессии (может быть NULL)
    payload         TEXT    NOT NULL,           -- JSON с полными данными события
    status          TEXT    NOT NULL DEFAULT 'NEW',
      -- NEW | QUEUED | SENDED | SERVER_SIDE_DONE | PENDING
    batch_id        TEXT,                       -- UUID батча (группировка при отправке)
    retry_count     INTEGER NOT NULL DEFAULT 0,
    last_error      TEXT,
    created_ts      TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
    updated_ts      TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);

CREATE INDEX idx_activity_status_type ON pending_activity(status, data_type);
CREATE INDEX idx_activity_batch ON pending_activity(batch_id);
```

**Почему JSON `payload`:**
- Разные типы данных имеют разную структуру (focus interval ≠ mouse click ≠ audit event)
- JSON позволяет хранить произвольные данные без ALTER TABLE при добавлении новых полей
- SQLite нативно поддерживает JSON-функции (`json_extract`) для запросов
- При отправке payload десериализуется в конкретный DTO

**Примеры payload:**

```json
// FOCUS_INTERVAL
{
  "process_name": "chrome.exe",
  "window_title": "GitHub - Mozilla Firefox",
  "is_browser": true,
  "browser_name": "Chrome",
  "domain": "github.com",
  "started_at": "2026-03-18T10:15:00Z",
  "ended_at": "2026-03-18T10:15:30Z",
  "duration_ms": 30000,
  "window_x": 0, "window_y": 0,
  "window_width": 1920, "window_height": 1040,
  "is_maximized": true,
  "is_fullscreen": false,
  "is_idle": false,
  "monitor_index": 0
}

// MOUSE_CLICK
{
  "x": 512, "y": 340,
  "button": "left", "click_type": "single",
  "ui_element_type": "Button", "ui_element_name": "Submit",
  "process_name": "chrome.exe",
  "window_title": "Jira",
  "segment_id": "...", "segment_offset_ms": 15200
}

// KEYBOARD_METRIC
{
  "keystroke_count": 47,
  "has_typing_burst": true,
  "interval_start": "2026-03-18T10:15:00Z",
  "interval_end": "2026-03-18T10:15:10Z",
  "process_name": "WINWORD.EXE"
}

// CLIPBOARD
{
  "action": "copy",
  "source_process": "chrome.exe",
  "content_type": "text",
  "content_length": 256,
  "content_hash": "a3f2b8c1d4e5f6...",  // SHA-256 hash содержимого
  "segment_id": "...", "segment_offset_ms": 42100
}

// AUDIT_EVENT
{
  "event_type": "PROCESS_START",
  "event_ts": "2026-03-18T10:14:55Z",
  "details": {"process_name": "chrome.exe", "pid": 12345, "exe_path": "C:\\...\\chrome.exe"}
}
```

**Миграция существующих таблиц:**
- `pending_segments` (старая, с `uploaded`): переименовать, создать новую, перенести: `uploaded=0 → status=PENDING`, `uploaded=1 → status=SERVER_SIDE_DONE`
- `audit_events` (старая, с `uploaded`): перенести данные в `pending_activity` с `data_type=AUDIT_EVENT`, `uploaded=0 → status=PENDING`, `uploaded=1 → status=SERVER_SIDE_DONE`

### 2.2 Процесс 1: Collector (сбор ВСЕХ данных → SQLite)

Collector объединяет все текущие источники данных. Каждый источник теперь **только пишет в SQLite** со статусом `NEW` и ничего не знает об отправке.

| Источник | Текущее поведение | Новое поведение |
|----------|------------------|-----------------|
| `WatchSegments` | `UploadQueue.EnqueueAsync()` | `LocalDatabase.InsertSegment(status: NEW)` |
| `FocusIntervalSink` | `ConcurrentQueue` (RAM) → HTTP POST каждые 30s | `LocalDatabase.InsertActivity(FOCUS_INTERVAL, payload, status: NEW)` |
| `InputEventSink` | `ConcurrentQueue` (RAM) → HTTP POST каждые 30s | `LocalDatabase.InsertActivity(MOUSE_CLICK/KEYBOARD_METRIC/SCROLL/CLIPBOARD, payload, status: NEW)` |
| `AuditEventSink` | SQLite `audit_events` + HTTP POST каждые 30s | `LocalDatabase.InsertActivity(AUDIT_EVENT, payload, status: NEW)` |

**Ключевые изменения по каждому sink'у:**

**`FocusIntervalSink`** → переписать `Publish()`:
- Вместо `_queue.Enqueue(interval)` → `LocalDatabase.InsertActivity("FOCUS_INTERVAL", JsonSerializer.Serialize(interval), sessionId)`
- Удалить `BackgroundService` flush-loop (больше не отправляет сам)
- Удалить `_queue` (`ConcurrentQueue`) — больше не нужна

**`InputEventSink`** → переписать `HandleInputEvents()`:
- Вместо `_mouseClicks.Enqueue(...)` → `LocalDatabase.InsertActivity("MOUSE_CLICK", json, sessionId)`
- То же для `KEYBOARD_METRIC`, `SCROLL`, `CLIPBOARD`
- Удалить `BackgroundService` flush-loop
- Удалить все `ConcurrentQueue` поля

**`AuditEventSink`** → переписать `Publish()`:
- Вместо `_db.SaveAuditEvent(...)` + `_queue.Enqueue(...)` → `LocalDatabase.InsertActivity("AUDIT_EVENT", json, sessionId)`
- Удалить `BackgroundService` flush-loop
- Удалить `_queue`

**`WatchSegments`** (в `CommandHandler`):
- Вместо `UploadQueue.EnqueueAsync(segmentInfo)` → `LocalDatabase.InsertSegment(filePath, sessionId, seqNum, sizeBytes, status: "NEW")`

**Результат:** Collector = 4 независимых источника, каждый **только INSERT в SQLite**. Никакой сетевой логики.

### 2.3 Поток 3: Uploader (новый класс `DataSyncService` — отдельный `BackgroundService`)

**Uploader работает в отдельном потоке**, непрерывно, независимо от heartbeat. Heartbeat лишь обновляет флаг `server_available`.

#### Взаимодействие Heartbeat ↔ Uploader

```
HeartbeatService (поток 2):                DataSyncService (поток 3):
    │                                          │
    ├── PUT /heartbeat                         │ (ждёт server_available)
    │    ↓                                     │
    │   200 OK                                 │
    │    ↓                                     │
    │   _serverAvailable = true  ──────────────► ManualResetEventSlim.Set()
    │   обработать commands/settings           │
    │    ↓                                     │ (проснулся, начинает round-robin)
    │   await 30s                              ├── batch activity
    │                                          ├── 1 video segment
    │   (через 30s)                            ├── batch activity
    ├── PUT /heartbeat                         ├── 1 video segment
    │    ↓                                     │   ...
    │   200 OK                                 │ (продолжает работать)
    │   _serverAvailable = true (уже true)     │
    │    ↓                                     │
    │   await 30s                              │
    │                                          │
    │   (через 30s)                            │
    ├── PUT /heartbeat                         │
    │    ↓                                     │
    │   TIMEOUT / 5xx                          │
    │   _serverAvailable = false ──────────────► (завершает текущий запрос,
    │    ↓                                     │  останавливается)
    │   backoff 60s                            │ (ждёт server_available)
```

#### Round-Robin алгоритм

**Вместо "сначала activity, потом video"** — чередование. Это гарантирует, что видеосегменты не "голодают" при большом объёме activity-данных, и наоборот.

```
DataSyncService.ExecuteAsync():
    while (!cancellationToken.IsCancellationRequested)
    {
        // Ждать пока сервер доступен
        _serverAvailableEvent.Wait(cancellationToken);

        // Round-robin loop: пока есть данные И сервер доступен
        while (_serverAvailable && HasPendingData())
        {
            // Шаг A: batch activity (focus + input + audit за один шаг)
            await SendActivityBatchAsync();

            if (!_serverAvailable) break;

            // Шаг B: 1 видеосегмент
            await SendOneVideoSegmentAsync();

            if (!_serverAvailable) break;
        }

        // Если данных нет — подождать 5s перед следующей проверкой
        if (_serverAvailable && !HasPendingData())
            await Task.Delay(5000, cancellationToken);
    }
```

**Один шаг round-robin:**

```
Шаг A: SendActivityBatchAsync()
    ├── Focus Intervals (до 100 записей) → POST /focus-intervals
    ├── Input Events (до 500 записей)    → POST /input-events
    └── Audit Events (до 100 записей)    → POST /audit-events
    Итого: 3 HTTP-запроса, ~150ms

Шаг B: SendOneVideoSegmentAsync()
    └── 1 сегмент: SHA-256 → presign → PUT S3 → confirm
    Итого: 3 HTTP + 1 S3 PUT, ~500ms-2s
```

**Полный round-robin цикл (A→B) ≈ 0.7-2.2s.** За 30-секундный heartbeat-интервал отправляется ~15-40 сегментов + соответствующий объём activity.

#### Поведение при переполнении

Если Uploader **не успевает** отправить все данные между heartbeat'ами — это нормально:
- Heartbeat **не ждёт** Uploader (разные потоки)
- Uploader продолжает отправку без остановки
- Heartbeat обновляет `server_available` и `upload_enabled` — Uploader реагирует
- Данные в SQLite накапливаются, но отправляются в порядке `id ASC`

#### Ограничения за один шаг round-robin

| Операция | Лимит | Причина |
|----------|-------|---------|
| Focus intervals | 100 записей | Серверный batch limit |
| Input events | 500 суммарно | Серверный batch limit |
| Audit events | 100 записей | Серверный batch limit |
| Video segments | **1 штука** | Тяжёлая операция (S3 PUT), чередуется с activity |

#### Обработка ошибок в Uploader

| Ситуация | Реакция |
|----------|---------|
| HTTP timeout/5xx | `_serverAvailable = false`, текущий batch → PENDING, ждать heartbeat |
| HTTP 401 | Вызвать `AuthManager.RefreshTokenAsync()`, retry |
| HTTP 400 (bad request) | Пометить записи как `PENDING`, increment `retry_count`, логировать ERROR |
| HTTP 409 (conflict) | Пометить как `SERVER_SIDE_DONE` (сервер уже имеет эти данные) |
| Файл сегмента не найден | Пометить `EVICTED`, пропустить |
| `retry_count > 10` | Пропустить запись на этой итерации, попробовать через 10 итераций |

### 2.4 Heartbeat — фиксированный 30s цикл, не блокируется Uploader'ом

**Текущая схема:** HeartbeatService работает с интервалом `NextHeartbeatSec` (default 30s). UploadQueue работает независимо.

**Новая схема:**

```
HeartbeatService.ExecuteAsync() loop (ВСЕГДА 30s):
    ↓
1. PUT /heartbeat → response
2. Обработать device_settings, pending_commands
3. if (response OK && response.upload_enabled)
       _serverAvailable = true
       _serverAvailableEvent.Set()       ◄── разбудить Uploader
   else
       _serverAvailable = false
       _serverAvailableEvent.Reset()     ◄── остановить Uploader
4. await Task.Delay(30s)                 ◄── ВСЕГДА 30 секунд, без backoff

DataSyncService.ExecuteAsync() (ОТДЕЛЬНЫЙ ПОТОК, непрерывный):
    ↓
1. _serverAvailableEvent.Wait()          ◄── ждать пока сервер доступен
2. Round-robin loop:
   A. SendActivityBatchAsync() — focus + input + audit
   B. SendOneVideoSegmentAsync() — 1 видеосегмент
   Повторять пока есть данные && _serverAvailable
3. Если данных нет — await Task.Delay(5s), goto 1
```

**Heartbeat НЕ вызывает Uploader** — он только обновляет флаг. Uploader работает в своём темпе:
- Если данных мало — отправит всё за несколько секунд и заснёт на 5s
- Если данных много — будет отправлять непрерывно, не дожидаясь следующего heartbeat
- Если сервер упал — heartbeat выставит `_serverAvailable = false`, Uploader остановится и будет ждать

### 2.5 Поведение при отсутствии heartbeat

**Сценарии и реакция:**

| Сценарий | Признак | Действие агента |
|----------|---------|-----------------|
| Сервер временно недоступен | Heartbeat HTTP timeout/5xx | Heartbeat продолжает ходить каждые 30s. `_serverAvailable = false` → Uploader спит. Collector продолжает записывать. Данные копятся в SQLite как NEW |
| Сеть отключена | Heartbeat connection refused / DNS error | То же. Heartbeat каждые 30s (не backoff!). Агент работает полностью автономно |
| Сервер вернул 401 (токен истёк) | HTTP 401 | AuthManager.RefreshTokenAsync(). При неудаче — `_serverAvailable = false`. Collector не останавливается |
| Длительный offline (>1 часа) | Счётчик последовательных неудач >120 | Логировать WARN. `SegmentFileManager.EvictOldSegments()` — при disk pressure удалять старые NEW-сегменты (FIFO). Collector продолжает |
| Heartbeat восстановился | Первый успешный heartbeat | `_serverAvailable = true` → Uploader немедленно просыпается и начинает round-robin. Порядок: `id ASC` (хронологический) |

**Автономный режим (offline-first):**
- Recorder **никогда** не останавливается из-за проблем с сетью
- SQLite — единственный source of truth для статусов
- При восстановлении связи — все `NEW` и `PENDING` сегменты отправляются в хронологическом порядке

### 2.6 Disk Eviction (изменения)

Текущий `SegmentFileManager` удаляет файлы по возрасту при превышении `MaxBufferBytes` (2 GB).

**Изменение:** перед удалением файла проверять статус в SQLite:
- `SERVER_SIDE_DONE` — удалять безусловно (уже на сервере)
- `NEW` / `PENDING` — удалять только при disk pressure, логировать WARNING ("segment evicted before upload")
- `QUEUED` / `SENDED` — **не удалять** (в процессе отправки)

При удалении файла со статусом `NEW`/`PENDING` — обновить статус на `EVICTED` (новый терминальный статус = данные потеряны).

---

## 3. Изменения на стороне сервера (Java)

### 3.1 Ключевое изменение: явное подтверждение (ACK)

**Текущая проблема:** в `kafka-only` режиме `POST /confirm` возвращает `202 Accepted` — это НЕ подтверждение сохранения, а подтверждение постановки в очередь. Агент не знает, был ли сегмент реально записан в PostgreSQL.

**Решение: новый endpoint для batch ACK**

```
POST /api/v1/ingest/ack-status
Content-Type: application/json
Authorization: Bearer <jwt>
X-Device-ID: <device_id>

{
  "segment_ids": ["uuid-1", "uuid-2", "uuid-3"]
}

Response 200:
{
  "results": [
    { "segment_id": "uuid-1", "status": "confirmed" },
    { "segment_id": "uuid-2", "status": "confirmed" },
    { "segment_id": "uuid-3", "status": "pending" }
  ]
}
```

**Логика:**
- `confirmed` — сегмент найден в PostgreSQL со статусом `confirmed` → агент может перевести в `SERVER_SIDE_DONE`
- `pending` — сегмент ещё в Kafka/не записан → агент оставляет `SENDED`, проверит на следующем heartbeat
- `not_found` — сегмент не найден → агент возвращает в `PENDING` для повторной отправки

### 3.2 Обновлённый flow confirm

Вариант A: **Синхронный confirm с ACK** (рекомендуемый)

```
Агент → POST /confirm → Сервер записывает в PostgreSQL → 200 OK { status: "confirmed" }
                                                          └─ ЭТО и есть ACK
```

В этом варианте не нужен отдельный `ack-status` endpoint. Сервер при confirm делает sync DB write и возвращает финальный статус. Агент сразу переходит в `SERVER_SIDE_DONE`.

**Отказ от `kafka-only` confirm** — возврат к `sync` режиму для confirm, но с оптимизациями:
- Убрать S3 HEAD check из confirm (экономия 20-100ms)
- Kafka dual-write оставить (для search-service, webhooks)
- DB write остаётся единственным синхронным шагом (~5ms)

Вариант B: **Асинхронный confirm + polling** (текущий `kafka-only` + новый endpoint)

```
Агент → POST /confirm → 202 Accepted (Kafka) → агент = SENDED
                                                    ↓
                      Следующий heartbeat → POST /ack-status [segment_ids]
                                                    ↓
                                    confirmed → SERVER_SIDE_DONE
                                    pending → остаётся SENDED
```

**Рекомендация:** Вариант A (синхронный). Причины:
- Проще (нет polling, нет нового endpoint)
- Confirm = 5ms DB write (без S3 HEAD) — приемлемо для 10K агентов
- Kafka dual-write для downstream consumers сохраняется
- Агент получает однозначный ответ за один HTTP-вызов

### 3.3 Изменения в IngestService.confirm()

```java
// Убрать: S3 HEAD check (переложить на фоновый процесс)
// Убрать: kafka-only mode для confirm
// Оставить: sync DB write + Kafka dual-write

public ConfirmResponse confirm(ConfirmRequest request, DevicePrincipal principal) {
    Segment segment = findAndValidate(request, principal);

    // 1. DB write (sync, ~5ms)
    segment.setStatus("confirmed");
    segmentRepository.save(segment);
    updateSessionStats(segment);

    // 2. Kafka publish (async, fire-and-forget)
    eventPublisher.publish(buildEvent(segment));

    // 3. Явный ACK агенту
    return new ConfirmResponse(segment.getId(), "confirmed", sessionStats);
}
```

### 3.4 Heartbeat response — добавить upload trigger

В heartbeat response добавить поле `upload_enabled`:

```json
{
  "server_ts": "...",
  "next_heartbeat_sec": 30,
  "upload_enabled": true,
  "pending_commands": [],
  "device_settings": {}
}
```

`upload_enabled = false` — сервер может временно запретить upload (maintenance, перегрузка). Агент продолжает записывать, но Uploader засыпает.

**Серверный throttling (двухуровневый):**

| Уровень | Механизм | Гранулярность | Когда |
|---------|----------|---------------|-------|
| 1 | nginx `limit_req` | per-device IP/device_id, 10 req/s | Всегда активен |
| 2 | `upload_enabled=false` в heartbeat | per-device | Сервер решает: DB overloaded, maintenance, disk full |

**Логика на сервере (DeviceService):**

```java
// В processHeartbeat():
boolean uploadEnabled = true;

// Проверка 1: общая нагрузка на БД (HikariCP pool utilization)
if (hikariPool.getActiveConnections() > hikariPool.getMaximumPoolSize() * 0.8) {
    uploadEnabled = false;  // pool загружен на >80%
}

// Проверка 2: ручной флаг (maintenance mode)
if (systemConfigService.isMaintenanceMode()) {
    uploadEnabled = false;
}

// Проверка 3: MinIO disk usage
if (minioHealthService.getDiskUsagePercent() > 90) {
    uploadEnabled = false;
}

response.setUploadEnabled(uploadEnabled);
```

---

## 4. Обработка edge cases

### 4.1 Дублирование сегментов

**Проблема:** агент отправил confirm, сервер записал в DB, но ответ потерялся (timeout). Агент помечает `PENDING`, отправляет повторно.

**Решение:** idempotent confirm. `UNIQUE(session_id, sequence_num)` в PostgreSQL. При повторном presign с тем же `session_id + sequence_num`:
- Если сегмент уже `confirmed` → вернуть `{ status: "confirmed" }` без повторной записи
- Если сегмент `uploaded` (presign сделан, confirm нет) → обновить, продолжить

### 4.2 Session Recovery

**Проблема:** агент перезапустился. В SQLite есть сегменты с `session_id` от прошлой сессии.

**Текущее решение (сохраняется):** ingest-gateway допускает upload в `completed`/`interrupted` сессии в течение 24 часов.

**Изменение:** Uploader при старте проверяет SQLite:
1. `SELECT DISTINCT session_id FROM pending_segments WHERE status NOT IN ('SERVER_SIDE_DONE', 'EVICTED')`
2. Для каждой сессии — проверить, существует ли она на сервере
3. Если сессия закрыта >24ч назад — пометить сегменты как `EVICTED`

### 4.3 Конкурентный доступ к SQLite

**Recorder** пишет новые записи (INSERT). **Uploader** обновляет статусы (UPDATE). Оба процесса — потоки внутри одного .NET-приложения.

**Решение:** SQLite в режиме WAL (`PRAGMA journal_mode=WAL`) — один writer + несколько reader'ов без блокировки. Для exclusive writes — `lock` на `LocalDatabase` instance (уже есть в текущем коде).

**Auto-vacuum:** При инициализации БД установить `PRAGMA auto_vacuum=INCREMENTAL` + периодически вызывать `PRAGMA incremental_vacuum` в `CleanupAsync()`. Это автоматически дефрагментирует файл после массовых DELETE без полного VACUUM (который блокирует БД).

### 4.4 Порядок отправки

Сегменты отправляются в порядке `id ASC` (хронологический порядок записи). Это гарантирует:
- Старые сегменты не "застревают" за новыми
- При восстановлении связи — сначала отправляются самые старые данные

### 4.5 Retry policy

| Attempt | Задержка до следующего retry | Когда |
|---------|------------------------------|-------|
| 1 | Следующий heartbeat (~30s) | Первая неудача |
| 2 | Следующий heartbeat (~30s) | Вторая неудача |
| 3–5 | Каждый второй heartbeat (~60s) | Стабильные ошибки |
| 6–10 | Каждый пятый heartbeat (~150s) | Хронические ошибки |
| >10 | Каждый десятый heartbeat (~300s) | Длительная проблема |

Реализация: `retry_count` в SQLite. Uploader пропускает сегменты, где `retry_count > N` и не прошло достаточно heartbeat'ов.

---

## 5. Миграция (пошаговый план)

### Фаза 1: Агент — SQLite-схема + Collector refactor

1. Добавить таблицу `pending_activity` в `LocalDatabase.cs`
2. Мигрировать `pending_segments` на новую схему (с `status` enum)
3. Мигрировать `audit_events` → `pending_activity`
4. Переписать `FocusIntervalSink` — убрать ConcurrentQueue + BackgroundService, только INSERT в SQLite
5. Переписать `InputEventSink` — убрать ConcurrentQueue + BackgroundService, только INSERT в SQLite
6. Переписать `AuditEventSink` — INSERT в `pending_activity` вместо `audit_events`
7. Изменить `WatchSegments` — INSERT в SQLite вместо UploadQueue.Enqueue

### Фаза 2: Агент — DataSyncService (отдельный поток) + Heartbeat refactor

1. Создать `ActivityUploader.cs` — batch-отправка focus/input/audit
2. Создать `DataSyncService.cs` как `BackgroundService` — отдельный поток, round-robin (activity batch ↔ 1 video)
3. Добавить `_serverAvailable` volatile bool + `ManualResetEventSlim` для координации с Heartbeat
4. Рефакторить `HeartbeatService` — фиксированный 30s цикл, обновление `_serverAvailable`, НЕ вызывает Uploader
5. Удалить `UploadQueue.cs` (BoundedChannel)
6. Упростить `SegmentUploader.cs` — убрать retry-логику
7. Добавить cleanup (GC) + `PRAGMA incremental_vacuum` для SQLite

### Фаза 3: Сервер — ACK + upload_enabled + throttling + clipboard hash

1. Убрать `kafka-only` mode из `IngestService.confirm()` — всегда sync DB write
2. Убрать S3 HEAD check из confirm (перенести в фоновый batch-процесс)
3. Оставить Kafka dual-write для downstream consumers
4. Добавить `upload_enabled` в heartbeat response + серверный throttling (HikariCP, maintenance, MinIO)
5. Добавить idempotent confirm (check existing segment before insert)
6. Обновить response для activity endpoints — возвращать `accepted/duplicates/total`
7. Flyway V40: `content_hash` колонка + partial index на `user_input_events`
8. Обновить `ClipboardEvent` DTO + `InputEventService` для сохранения `content_hash`

### Фаза 4: Тестирование

1. Unit-тесты: state machine переходы (все типы данных)
2. Integration: агент ↔ сервер (activity batch + video confirm + ACK)
3. Offline-режим: отключить сеть на 10 мин, проверить что ВСЕ данные синхронизируются
4. Crash recovery: убить агент в процессе записи, перезапустить, проверить что focus/input данные не потеряны
5. Disk pressure: заполнить буфер, проверить eviction policy
6. Duplicate handling: имитировать потерю ACK, проверить дедупликацию
7. Объём данных: 8 часов работы без сети → проверить SQLite size и скорость sync при reconnect

### Фаза 5: Деплой

1. Деплой серверных изменений (backward-compatible)
2. Выпуск новой версии агента
3. Мониторинг: метрики в heartbeat — segments + activity по типам

---

## 6. Метрики и мониторинг

Добавить в `heartbeat.metrics`:

```json
{
  "segments_new": 5,
  "segments_queued": 2,
  "segments_sended": 1,
  "segments_pending": 3,
  "segments_done": 142,
  "segments_evicted": 0,
  "activity_new": 230,
  "activity_pending": 12,
  "activity_done": 5840,
  "activity_by_type": {
    "focus_interval": { "new": 15, "pending": 2, "done": 1200 },
    "mouse_click": { "new": 120, "pending": 5, "done": 2800 },
    "keyboard_metric": { "new": 30, "pending": 2, "done": 600 },
    "scroll": { "new": 50, "pending": 3, "done": 1100 },
    "audit_event": { "new": 15, "pending": 0, "done": 140 }
  },
  "disk_buffer_bytes": 524288000,
  "db_size_bytes": 15728640,
  "oldest_unsent_segment_age_sec": 120,
  "oldest_unsent_activity_age_sec": 35
}
```

Сервер может мониторить:
- `segments_pending > 20` — проблема с upload видео (alert)
- `segments_evicted > 0` — потеря видеоданных (critical alert)
- `activity_pending > 500` — проблема с upload activity (alert)
- `oldest_unsent_segment_age_sec > 3600` — видео не уходят больше часа (warning)
- `oldest_unsent_activity_age_sec > 600` — activity-данные не уходят 10+ мин (warning)
- `db_size_bytes > 100MB` — SQLite разрастается (warning, нужна очистка SERVER_SIDE_DONE)

---

## 7. Сводка изменений

### Агент (C#)

| Компонент | Действие | Описание |
|-----------|----------|----------|
| `LocalDatabase.cs` | **Modify** | Новая `pending_segments` (с status enum) + новая `pending_activity`. Миграция старых данных. Методы `InsertActivity`, `GetPendingActivity`, `UpdateActivityStatus` |
| `WatchSegments` (CommandHandler) | **Modify** | INSERT в SQLite вместо UploadQueue.Enqueue |
| `UploadQueue.cs` | **Delete** | Заменён на DataSyncService |
| `DataSyncService.cs` | **Create** | Отдельный `BackgroundService`. Round-robin: batch activity ↔ 1 video. Управляется флагом `_serverAvailable` |
| `HeartbeatService.cs` | **Modify** | Фиксированный 30s цикл. Обновляет `_serverAvailable` флаг. НЕ вызывает Uploader напрямую |
| `SegmentUploader.cs` | **Modify** | Упростить: убрать retry-логику. Добавить ACK-проверку |
| `FocusIntervalSink.cs` | **Modify** | Убрать BackgroundService + ConcurrentQueue. Только INSERT в SQLite |
| `InputEventSink.cs` | **Modify** | Убрать BackgroundService + ConcurrentQueue. Только INSERT в SQLite |
| `AuditEventSink.cs` | **Modify** | Убрать BackgroundService + ConcurrentQueue. INSERT в `pending_activity` вместо `audit_events` |
| `SegmentFileManager.cs` | **Modify** | Учитывать статус при eviction |
| `AgentState.cs` | **Modify** | Метрики по статусам сегментов + activity |
| `ActivityUploader.cs` | **Create** | Batch-отправка activity: формирование запросов для focus/input/audit endpoints |

### Сервер (Java)

| Компонент | Действие | Описание |
|-----------|----------|----------|
| `IngestService.java` | **Modify** | Всегда sync confirm. Убрать S3 HEAD. Idempotent check |
| `IngestController.java` | **Modify** | Confirm всегда 200 (не 202). Response = явный ACK |
| `HeartbeatResponse.java` | **Modify** | Добавить `upload_enabled` |
| `DeviceService.java` | **Modify** | Добавить `upload_enabled` + throttling logic (HikariCP, maintenance, MinIO) |
| `InputEventService.java` | **Modify** | Сохранять `content_hash` для clipboard events |
| `ClipboardEvent DTO` | **Modify** | Добавить поле `content_hash` |
| `UserInputEvent.java` | **Modify** | Добавить колонку `content_hash` |
| Flyway migration | **Create** | `V40__clipboard_content_hash.sql`: ADD COLUMN `content_hash` + partial index |

### PostgreSQL миграция: clipboard content_hash

```sql
-- V40__clipboard_content_hash.sql

-- Добавить колонку content_hash в user_input_events
ALTER TABLE user_input_events ADD COLUMN content_hash TEXT;

-- Partial index: только clipboard events, по (tenant_id, content_hash)
-- Используется для быстрой корреляции copy↔paste
CREATE INDEX idx_input_events_clipboard_hash
  ON user_input_events (tenant_id, content_hash)
  WHERE event_type = 'clipboard' AND content_hash IS NOT NULL;
```

**Использование:** API-запрос `GET /activity/input-events?event_type=clipboard&content_hash=<sha256>` вернёт все clipboard-операции с одинаковым содержимым (copy в одном приложении → paste в другом). Индекс делает этот запрос O(log n) вместо full table scan.

---

## 8. Диаграмма последовательности (три потока + round-robin)

```
  Collector         Heartbeat         SQLite         DataSyncService      Server
     │                 │                │                │                   │
     │ FFmpeg segment  │                │                │                   │
     ├──INSERT NEW─────┼───────────────►│               │                   │
     │                 │                │                │ (ждёт server_     │
     │ FocusInterval   │                │                │  available)       │
     ├──INSERT NEW─────┼───────────────►│               │                   │
     │                 │                │                │                   │
     │                 ├──PUT /heartbeat─┼───────────────┼──────────────────►│
     │                 │◄───────────────┼───────────────┼──────────────────┤
     │                 │ 200 OK         │                │                   │
     │                 │                │                │                   │
     │                 │ server_available = true ────────► (проснулся!)      │
     │                 │                │                │                   │
     │                 │ await 30s      │                │ ══ Round 1A ══    │
     │ MouseClick      │                │                │                   │
     ├──INSERT NEW─────┼───────────────►│◄──SELECT──────┤                   │
     │                 │                │──rows─────────►│                   │
     │                 │                │◄──UPD QUEUED───┤                   │
     │                 │                │                ├──POST /focus──────►│
     │                 │                │                │◄──200 OK─────────┤
     │                 │                │◄──UPD DONE─────┤                   │
     │                 │                │                ├──POST /input──────►│
     │                 │                │                │◄──200 OK─────────┤
     │                 │                │◄──UPD DONE─────┤                   │
     │                 │                │                │                   │
     │                 │                │                │ ══ Round 1B ══    │
     │                 │                │◄──SELECT seg───┤                   │
     │                 │                │◄──UPD QUEUED───┤                   │
     │                 │                │                ├──POST /presign────►│
     │                 │                │                │◄──{id,url}────────┤
     │                 │                │                ├──PUT S3───────────►│
     │                 │                │                │◄──200─────────────┤
     │                 │                │                ├──POST /confirm────►│
     │                 │                │                │◄──200 confirmed───┤
     │                 │                │◄──UPD DONE─────┤                   │
     │                 │                │                ├──File.Delete      │
     │                 │                │                │                   │
     │                 │                │                │ ══ Round 2A ══    │
     │                 │                │                │ (продолжает...)   │
     │                 │                │                │                   │
     │                 ├──PUT /heartbeat─┼───────────────┼──────────────────►│
     │                 │◄───────────────┼───────────────┼──────────────────┤
     │                 │ 200 OK         │                │ (не прерывается) │
     │                 │ server_available                │                   │
     │                 │  = true (уже)  │                │                   │
     │                 │ await 30s      │                │                   │
```

---

## 9. Очистка SQLite (GC)

`pending_activity` будет расти быстро (focus interval каждые 5s, mouse clicks, keyboard каждые 10s). Нужна фоновая очистка.

**Правила:**

| Условие | Действие | Причина |
|---------|----------|---------|
| `SERVER_SIDE_DONE`, `updated_ts` > 1 час | DELETE из `pending_activity` | Уже на сервере, буфер для диагностики |
| `SERVER_SIDE_DONE` сегмент | DELETE из `pending_segments` | Файл уже удалён |
| `EVICTED`, `updated_ts` > 24 часа | DELETE | Данные потеряны, запись не нужна |
| **Любой статус, `created_ts` > 72 часа** | **DELETE + EVICTED** | **Максимальная глубина хранения — 72 часа. Данные старше 3 суток удаляются безусловно** |

> **72 часа** — жёсткий retention. Если агент был offline больше 3 дней, данные старше этого порога удаляются из SQLite без отправки на сервер. Это защищает диск от переполнения и гарантирует предсказуемый объём локального хранилища.

**Реализация:** `DataSyncService.CleanupAsync()` — вызывать когда Uploader засыпает (нет данных для отправки):

```sql
-- 1. Очистка завершённых activity-записей (старше 1 часа)
DELETE FROM pending_activity
  WHERE status = 'SERVER_SIDE_DONE'
    AND updated_ts < strftime('%Y-%m-%dT%H:%M:%fZ', 'now', '-1 hour');

-- 2. Очистка evicted activity-записей (старше 24 часов)
DELETE FROM pending_activity
  WHERE status = 'EVICTED'
    AND updated_ts < strftime('%Y-%m-%dT%H:%M:%fZ', 'now', '-1 day');

-- 3. Очистка завершённых/evicted сегментов
DELETE FROM pending_segments
  WHERE status IN ('SERVER_SIDE_DONE', 'EVICTED');

-- 4. RETENTION 72 ЧАСА: удалить ВСЕ записи старше 3 суток (любой статус)
DELETE FROM pending_activity
  WHERE created_ts < strftime('%Y-%m-%dT%H:%M:%fZ', 'now', '-3 days');

-- 5. RETENTION 72 ЧАСА: удалить сегменты старше 3 суток + файлы
-- (сначала SELECT для получения file_path, потом DELETE)
-- Реализация в C#: File.Delete для каждого file_path, затем DELETE FROM pending_segments
DELETE FROM pending_segments
  WHERE created_ts < strftime('%Y-%m-%dT%H:%M:%fZ', 'now', '-3 days');

-- 6. Incremental vacuum — освободить место после DELETE
PRAGMA incremental_vacuum;
```

**SQLite init (при создании БД):**

```sql
PRAGMA auto_vacuum = INCREMENTAL;  -- ВАЖНО: должен быть ДО создания таблиц
PRAGMA journal_mode = WAL;
```

> `auto_vacuum=INCREMENTAL` — SQLite помечает освобождённые страницы, но не возвращает их ОС автоматически. `PRAGMA incremental_vacuum` освобождает их по запросу — быстро и без блокировки всей БД (в отличие от полного `VACUUM`).

**Ожидаемый объём данных (на 1 агента, за 8-часовой рабочий день):**

| Тип | Частота | Записей/день | ~JSON размер | ~SQLite/день |
|-----|---------|-------------|-------------|-------------|
| Focus Interval | каждые 5s (при смене окна) | ~2 000 | ~300 bytes | ~600 KB |
| Mouse Click | ~500/час | ~4 000 | ~250 bytes | ~1 MB |
| Keyboard Metric | каждые 10s | ~2 880 | ~150 bytes | ~430 KB |
| Scroll | ~200/час | ~1 600 | ~150 bytes | ~240 KB |
| Clipboard | ~20/час | ~160 | ~200 bytes | ~32 KB |
| Audit Event | ~50/день | ~50 | ~200 bytes | ~10 KB |
| **Итого** | | **~10 700** | | **~2.3 MB** |

**Максимальный объём SQLite при 72-часовом retention:**
- Activity: ~2.3 MB/день × 3 дня = **~7 MB**
- Сегменты видео: зависит от disk eviction (MaxBufferBytes = 2 GB), но записи в SQLite ~50 bytes × 3 дня ≈ незначительно
- **Гарантированный потолок SQLite: ~10-15 MB** (без учёта WAL-файла)

---

## 10. Серверный ACK для activity-данных

**Текущие activity-endpoints** (`/focus-intervals`, `/input-events`, `/audit-events`) возвращают `200 OK` при успешном сохранении в PostgreSQL. Это уже является синхронным ACK — аналогично рекомендованному Варианту A для видео.

**Дополнительное изменение:** добавить в response body подтверждение с количеством принятых записей:

```json
// POST /activity/focus-intervals → 200 OK
{
  "accepted": 87,
  "duplicates": 13,
  "total": 100
}

// POST /activity/input-events → 200 OK
{
  "accepted": {
    "mouse_clicks": 45,
    "keyboard_metrics": 12,
    "scroll_events": 20,
    "clipboard_events": 0
  },
  "duplicates": 3,
  "total": 80
}

// POST /audit-events → 200 OK
{
  "accepted": 15,
  "duplicates": 0,
  "total": 15
}
```

Агент при получении `200 OK` → `SERVER_SIDE_DONE` для всех записей в батче. Сервер гарантирует, что дубликаты (по `event_id`) игнорируются без ошибки.

---

## 11. Решённые вопросы (v3)

| # | Вопрос | Решение |
|---|--------|---------|
| 1 | Batch vs sequential для видео | **По одному.** Простота, предсказуемость. Batch — в будущем |
| 2 | Один поток upload или несколько | **Один поток**, но **отдельный от heartbeat**. Heartbeat всегда 30s, Uploader работает непрерывно в своём потоке |
| 3 | `upload_enabled = false` — backoff? | **Нет backoff.** Просто на каждом heartbeat проверять флаг |
| 4 | Приоритет activity vs video | **Round-robin:** batch activity → 1 video → batch activity → 1 video → ... |
| 5 | Clipboard hash | **Да, хранить SHA-256 hash содержимого** для корреляции copy↔paste |
| 6 | SQLite VACUUM | **`auto_vacuum=INCREMENTAL`** при создании БД + `PRAGMA incremental_vacuum` при cleanup |

## 12. Решённые вопросы (v3.1)

| # | Вопрос | Решение |
|---|--------|---------|
| 7 | Серверный throttling `upload_enabled=false` | **Да, нужен.** Сервер при перегрузке ставит `upload_enabled=false` в heartbeat response. Nginx rate-limit — первая линия, `upload_enabled` — вторая (гранулярная, per-device) |
| 8 | Retention policy для `pending_activity` | **72 часа максимум.** Данные старше 72 часов удаляются из SQLite безусловно (и сегменты, и activity). Disk eviction — дополнительная защита при disk pressure |
| 9 | Серверный индекс `content_hash` для clipboard | **Да, нужен.** Индекс по `(tenant_id, content_hash)` на `user_input_events` WHERE `event_type='clipboard'` для быстрой корреляции copy↔paste |
