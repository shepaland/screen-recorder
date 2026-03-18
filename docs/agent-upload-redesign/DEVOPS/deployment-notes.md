# Deployment Notes: agent-upload-redesign Phase 1

**Дата:** 2026-03-18
**Среда:** test (shepaland-cloud, namespace test-screen-record)
**Сервисы:** ingest-gateway, control-plane

## Что задеплоено

### ingest-gateway
- Убран kafka-only confirm, добавлен S3 HEAD check для idempotent confirm
- V42 миграция: колонка `content_hash` в `user_input_events` для дедупликации clipboard-событий
- Enriched activity responses

### control-plane
- `upload_enabled` в heartbeat-ответе
- ThrottleConfig

## Проблемы и решения

### 1. Дублирующийся метод mapEnrichedRow (RecordingService.java)
**Симптом:** Compilation error — method already defined at line 534.
**Причина:** В ходе рефакторинга Phase 1 в RecordingService.java оказалось два метода `mapEnrichedRow(Object[])` — старый (строки 420-463, индексы 13/14 для hostname/employeeName) и новый (строки 534-581, корректные индексы 14/15 с учётом нового столбца os_username).
**Решение:** Удалён старый метод (строки 413-463), оставлен новый.

### 2. Конфликт версий Flyway V40
**Симптом:** В src/main/resources/db/migration два файла с версией V40:
- V40__add_recorded_at_to_segments.sql (старый, уже применён)
- V40__clipboard_content_hash.sql (новый из Phase 1)
**Причина:** Новый файл получил номер версии уже занятой миграции.
**Решение:** Новый файл переименован в V42__clipboard_content_hash.sql (следующая свободная после V41).

### 3. Schema-validation: missing column content_hash
**Симптом:** CrashLoopBackOff — Hibernate не находит колонку content_hash в user_input_events.
**Причина:** Flyway history на test содержала только V37 (V38-V41 применялись напрямую через psql, не через Flyway). V42 не была применена.
**Решение:**
1. Колонка `content_hash` и индекс добавлены через psql: `ALTER TABLE user_input_events ADD COLUMN IF NOT EXISTS content_hash TEXT;`
2. Миграции V38-V42 зарегистрированы в flyway_schema_history с корректными CRC32 checksums.

## Статус деплоя

| Сервис | Pod | Статус | Restarts |
|--------|-----|--------|----------|
| ingest-gateway | ingest-gateway-7c94797f7f-ql7m2 | Running | 0 |
| control-plane | control-plane-67fd9c9d95-ww764 | Running | 0 |

## Проверка

- ingest-gateway: presign URL генерируется, segment confirm работает (видно в логах)
- control-plane: Kafka consumer webhook-dispatcher подключён, DispatcherServlet инициализирован
- Flyway: все миграции V1-V42 зарегистрированы, validation прошла без ошибок

## URL для тестирования

- **test:** https://services-test.shepaland.ru/screenrecorder
- **Учётные данные:** maksim / #6TY0N0d (tenant "Тест 1")
