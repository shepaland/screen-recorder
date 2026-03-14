# SA: macOS Agent — Windows Parity (4 доработки)

## 1. SessionManager 409 Retry

**Бизнес-сценарий**: При создании сессии сервер может вернуть 409 Conflict (stale session exists). Агент должен найти и закрыть stale sessions, затем повторить создание.

**Системный сценарий**:
1. `createSession()` получает 409 Conflict
2. Вызвать `GET /api/ingest/v1/ingest/recordings?device_id={id}&status=active&size=10`
3. Для каждой найденной сессии вызвать `PUT /sessions/{id}/end`
4. Повторить `createSession()`

**Изменяемые файлы**:
- `SessionManager.swift` — добавить 409 catch + closeActiveSessionsForDevice + retry
- `APIClient.swift` — добавить `get<T>()` метод
- `APIEndpoints.swift` — добавить `recordings(deviceId:)` endpoint
- `DTOs.swift` — добавить `RecordingsListResponse`, `RecordingItem`

---

## 2. BrowserDomainParser — 128 маппингов

**Бизнес-сценарий**: Расширить маппинги заголовков окон до 128 (Windows parity).

**Системный сценарий**: Перенести все маппинги из Windows `KnownTitleDomains` dictionary. Переключить с lowercase-contains на exact-match по заголовку и по частям (до/после разделителя).

**Изменяемые файлы**:
- `BrowserDomainParser.swift` — заменить titleMappings на dictionary, добавить все 128 маппингов, реализовать multi-strategy resolution как в Windows

---

## 3. Session Rotation (60 мин)

**Бизнес-сценарий**: Ротация recording session каждые 60 минут для предотвращения бесконечно длинных сессий.

**Системный сценарий**:
1. `ServerConfig` получает `sessionMaxDurationMin` (default 60)
2. `AgentService` запускает session rotation timer
3. По истечении — остановить текущую запись, закрыть сессию, создать новую, начать запись

**Изменяемые файлы**:
- `ServerConfig.swift` — добавить `sessionMaxDurationMin`
- `AgentService.swift` — добавить session rotation timer + `rotateSession()`

---

## 4. ProcessWatcher (NSWorkspace)

**Бизнес-сценарий**: Мониторинг запуска/завершения приложений для аудита.

**Системный сценарий**:
1. Подписаться на `NSWorkspace.didLaunchApplicationNotification` и `didTerminateApplicationNotification`
2. Фильтровать системные процессы (com.apple.*)
3. 3-секундный debounce для short-lived процессов
4. Публиковать `PROCESS_START` / `PROCESS_STOP` audit events

**Новые файлы**:
- `Platform/Tracking/ProcessWatcher.swift`

**Изменяемые файлы**:
- `AgentService.swift` — инициализировать и запустить ProcessWatcher
- `AuditEvent.swift` — добавить `processStart`, `processStop` event types

---

## Декомпозиция

| # | Компонент | Файлы | Оценка |
|---|-----------|-------|--------|
| BE-1 | SessionManager 409 retry | 4 файла | ~60 LOC |
| BE-2 | BrowserDomainParser 128 | 1 файл | ~100 LOC delta |
| BE-3 | Session rotation | 2 файла | ~40 LOC |
| BE-4 | ProcessWatcher | 2 новых, 2 изменённых | ~90 LOC |
