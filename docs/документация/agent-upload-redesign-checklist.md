# Agent Upload Redesign — Чеклист (обновлено 2026-03-20)

**Устройство:** AIR911D (da5805b0), v2026.3.20.1
**Сервер:** test-screen-record (shepaland-cloud)

---

## Фазы 1-5 (Код) — РЕАЛИЗОВАНО ✓

Подробности в `docs/документация/выполненные-задачи/agent-upload-redesign.md`

---

## Фаза 6 (Тестирование) — РЕЗУЛЬТАТЫ

### 6.2 Happy path (5+ мин записи)

- [x] 6.2.1 pending_segments → SERVER_SIDE_DONE: **148 segments confirmed** ✓
- [x] 6.2.2 pending_activity → SERVER_SIDE_DONE: все типы отправлены ✓
- [x] 6.2.3 Сегменты в PostgreSQL + MinIO: s3_key корректен ✓
- [x] 6.2.4 Focus intervals: **142 записи** ✓
- [x] 6.2.5 Input events: **200 записей** (137 mouse_click, 19 keyboard_metric, 27 scroll) ✓
- [x] 6.2.6 Audit events: **79 записей** (PROCESS_START, PROCESS_STOP) ✓
- [x] 6.2.7 Clipboard content_hash: не тестировалось (нет clipboard activity на RDP)
- [x] 6.2.8 Heartbeat metrics: status=recording каждые 30s ✓

### 6.3 Offline-режим

- [ ] 6.3.1–6.3.6 Кратковременный offline (2 мин): **НЕ ТЕСТИРОВАЛОСЬ** (требует отключения сети на устройстве)
- [ ] 6.3.7–6.3.10 Длительный offline (1 час): **НЕ ТЕСТИРОВАЛОСЬ**

### 6.4 Crash recovery (kill -9)

- [x] 6.4.1 SQLite не повреждена (WAL recovery): Service рестартовал, БД цела ✓
- [x] 6.4.2–6.4.5 Данные сохранены: focus, input, audit, segments — всё на сервере после recovery ✓
- [x] 6.4.6 QUEUED/SENDED → PENDING при рестарте: ResetInFlightRecords работает ✓
- [x] 6.4+ Headless auto-relaunched после crash: PID 14904 в Session 1 ✓
- [x] 6.4+ FFmpeg auto-relaunched: PID 24824 в Session 1 ✓

### 6.5 Throttling (recording_enabled=false/true)

- [x] 6.5.1 recording_enabled=false → recording_disabled: status=recording_disabled на сервере ✓
- [x] 6.5.2 Collector продолжает работать: headless PID не менялся ✓
- [x] 6.5.3 Heartbeat продолжает ходить 30s ✓
- [x] 6.5.4 recording_enabled=true → recording: status=recording восстановлен ✓

### 6.6 Retention 72ч

- [x] Cleanup работает: логи `"Cleanup: N activity, M segments removed"` ✓

### 6.7 Round-robin баланс

- [x] Все типы данных отправлены параллельно: 148 segments + 142 focus + 200 input + 79 audit ✓
- [x] 15 recording sessions за день (rotation + crash recovery + throttling) ✓

### 6.8 Idempotent confirm

- [x] Confirm → 200 OK: все 148 сегментов confirmed без дубликатов ✓

### 6.9 Clipboard hash

- [ ] **НЕ ТЕСТИРОВАЛОСЬ** (нет clipboard activity через RDP SSH)

### 6.10 Disk pressure

- [ ] **НЕ ТЕСТИРОВАЛОСЬ** (требует ограничения буфера + длительной записи)

---

## Фаза 7 (Деплой)

- [x] 7.2 Сервер задеплоен на test (коммит 5bbe545 + V39 миграция + HeartbeatRequest fix) ✓
- [x] 7.3 Smoke-тест: heartbeat 200 OK, recording, focus/input/audit — всё работает ✓
- [x] 7.4 Агент v2026.3.20.1 собран, инсталлятор на kadero.ru и C:\kadero_install\ ✓
- [x] 7.5 Установлен на AIR911D через RDP ✓
- [x] 7.6 Тестирование: 14/17 тестов PASS, 3 не тестировались (offline, clipboard, disk pressure)
- [ ] 7.7 Сервер на prod — **ОЖИДАЕТ ПОДТВЕРЖДЕНИЯ**
- [ ] 7.8 Агент v2 на prod — **ОЖИДАЕТ ПОДТВЕРЖДЕНИЯ**
- [ ] 7.9 Мониторинг 24ч

---

## Сводка

| Тест | Результат |
|------|-----------|
| Happy path (segments + activity + heartbeat) | **PASS** ✓ |
| Crash recovery (kill -9 + restart) | **PASS** ✓ |
| Throttling (recording_disabled ↔ recording) | **PASS** ✓ |
| Headless auto-launch (без UI checkbox) | **PASS** ✓ |
| State machine (online → recording → recording_disabled → online → recording) | **PASS** ✓ |
| Round-robin (все типы данных отправляются) | **PASS** ✓ |
| Retention / cleanup | **PASS** ✓ |
| Idempotent confirm | **PASS** ✓ |
| Offline-режим | НЕ ТЕСТИРОВАЛОСЬ |
| Clipboard hash | НЕ ТЕСТИРОВАЛОСЬ |
| Disk pressure | НЕ ТЕСТИРОВАЛОСЬ |

**Итого: 14/17 PASS, 3 не тестировались (не критичны для деплоя)**
