# Декомпозиция: Редизайн агент ↔ сервер

> **Принцип:** Zero Downtime. Каждая фаза backward-compatible — старые агенты работают с новым сервером, новые агенты работают со старым сервером. Никаких breaking changes без переходного периода.

## Фазы

| # | Фаза | Где | Зависит от | Суть |
|---|------|-----|------------|------|
| 1 | [Сервер: подготовка](./01-server-prepare.md) | Java (ingest-gw, control-plane) | — | `upload_enabled`, sync confirm, clipboard hash, throttling. Без breaking changes |
| 2 | [Агент: SQLite v2](./02-agent-sqlite.md) | C# (agent) | — | Новые таблицы, auto_vacuum, миграция данных. Без изменений в сетевой логике |
| 3 | [Агент: Collector refactor](./03-agent-collector.md) | C# (agent) | Фаза 2 | Sink'и пишут в SQLite вместо RAM. BackgroundService flush-loops удалены |
| 4 | [Агент: DataSyncService](./04-agent-uploader.md) | C# (agent) | Фазы 2, 3 | Новый BackgroundService: round-robin, server_available, cleanup. Удаление UploadQueue |
| 5 | [Агент: Heartbeat refactor](./05-agent-heartbeat.md) | C# (agent) | Фаза 4 | Heartbeat обновляет server_available, не вызывает upload. Фиксированный 30s цикл |
| 6 | [Интеграционное тестирование](./06-testing.md) | Обе стороны | Фазы 1–5 | Offline, crash recovery, throttling, retention, round-robin |
| 7 | [Деплой и rollback](./07-deploy.md) | Инфраструктура | Фаза 6 | Деплой сервера → деплой агента. Rollback план |

## Порядок и параллелизм

```
Фаза 1 (сервер) ──────────────────────┐
                                       ├──► Фаза 6 (тестирование) ──► Фаза 7 (деплой)
Фаза 2 → Фаза 3 → Фаза 4 → Фаза 5 ──┘
(агент — последовательная цепочка)
```

Фаза 1 (сервер) и Фазы 2–5 (агент) можно делать **параллельно**. Сервер — backward-compatible, агент — forward-compatible. Тестирование — после обоих.

## Критерии готовности каждой фазы

- Компиляция без ошибок
- Существующие тесты проходят
- Старая функциональность не сломана (backward compatibility)
- Новая функциональность покрыта unit-тестами
