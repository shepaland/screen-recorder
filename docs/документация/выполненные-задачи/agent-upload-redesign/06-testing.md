# Фаза 6: Интеграционное тестирование

> **Цель:** Проверить все сценарии взаимодействия нового агента с новым сервером. Убедиться в backward compatibility.
>
> **Зависит от:** Фазы 1–5
> **Блокирует:** Фазу 7 (деплой)

---

## 6.1 Матрица совместимости

| Агент | Сервер | Ожидаемый результат |
|-------|--------|---------------------|
| Старый (v1) | Старый (v1) | Работает как раньше |
| Старый (v1) | Новый (v2) | Работает. Игнорирует `upload_enabled`, enriched response |
| Новый (v2) | Старый (v1) | Работает. `upload_enabled` default true. Confirm 200 OK → SERVER_SIDE_DONE |
| Новый (v2) | Новый (v2) | Полный функционал: round-robin, throttling, retention |

**Тесты:**
- [ ] 6.1.1 Старый агент + новый сервер: presign → PUT → confirm → 200 OK
- [ ] 6.1.2 Старый агент + новый сервер: heartbeat содержит `upload_enabled` — агент не падает
- [ ] 6.1.3 Новый агент + старый сервер: heartbeat без `upload_enabled` → upload работает
- [ ] 6.1.4 Новый агент + новый сервер: полный round-robin цикл

---

## 6.2 Основной сценарий (happy path)

**Предусловия:** Новый агент + новый сервер. Сеть стабильна.

**Шаги:**
1. Запустить агент
2. Начать запись (auto-start или команда)
3. Работать 5 минут: переключать приложения, кликать, скроллить, копировать текст
4. Остановить запись

**Проверка:**
- [ ] 6.2.1 В SQLite `pending_segments`: все записи → `SERVER_SIDE_DONE`
- [ ] 6.2.2 В SQLite `pending_activity`: все записи → `SERVER_SIDE_DONE`
- [ ] 6.2.3 На сервере: сегменты в PostgreSQL + MinIO
- [ ] 6.2.4 На сервере: focus intervals в `app_focus_intervals`
- [ ] 6.2.5 На сервере: input events в `user_input_events`
- [ ] 6.2.6 На сервере: audit events в `device_audit_events`
- [ ] 6.2.7 На сервере: clipboard events имеют `content_hash`
- [ ] 6.2.8 Heartbeat metrics содержат актуальные counters

---

## 6.3 Offline-режим

**Сценарий 1: Кратковременный offline (2 минуты)**

1. Запустить агент, начать запись
2. Отключить сеть (disable adapter)
3. Работать 2 минуты
4. Включить сеть

**Проверка:**
- [ ] 6.3.1 Запись не прерывается
- [ ] 6.3.2 Данные копятся в SQLite как NEW
- [ ] 6.3.3 Heartbeat продолжает ходить каждые 30s (connection refused, но не падает)
- [ ] 6.3.4 После восстановления: DataSyncService просыпается и отправляет всё
- [ ] 6.3.5 Порядок: хронологический (id ASC)
- [ ] 6.3.6 Дубликатов на сервере нет

**Сценарий 2: Длительный offline (1 час)**

1. Запустить агент, начать запись
2. Отключить сеть на 1 час
3. Включить сеть

**Проверка:**
- [ ] 6.3.7 Все данные за час сохранены в SQLite
- [ ] 6.3.8 SQLite size разумный (~2-3 MB activity + видеофайлы)
- [ ] 6.3.9 После восстановления: синхронизация завершается (может занять несколько минут)
- [ ] 6.3.10 Все данные корректно на сервере

---

## 6.4 Crash recovery

**Сценарий: Kill -9 агента**

1. Запустить агент, начать запись
2. Работать 2 минуты
3. Kill -9 (или `taskkill /F /IM KaderoAgent.exe`)
4. Подождать 10 секунд
5. Запустить агент заново

**Проверка:**
- [ ] 6.4.1 SQLite не повреждена (WAL recovery)
- [ ] 6.4.2 Focus intervals до крэша — сохранены в SQLite (не потеряны!)
- [ ] 6.4.3 Input events до крэша — сохранены в SQLite
- [ ] 6.4.4 Audit events до крэша — сохранены
- [ ] 6.4.5 Pending segments — сохранены, продолжат отправку
- [ ] 6.4.6 Нет дублирования: QUEUED/SENDED записи → PENDING при рестарте

> **Важно:** При старте агента проверить QUEUED/SENDED записи и вернуть их в PENDING (они не были завершены):
> ```sql
> UPDATE pending_activity SET status = 'PENDING' WHERE status IN ('QUEUED', 'SENDED');
> UPDATE pending_segments SET status = 'PENDING' WHERE status IN ('QUEUED', 'SENDED');
> ```

---

## 6.5 Throttling

**Сценарий: Сервер выставляет upload_enabled=false**

1. Запустить агент, начать запись
2. Выставить `THROTTLE_MAINTENANCE_MODE=true` на сервере
3. Подождать 1 heartbeat (30s)
4. Убедиться что upload остановился
5. Снять maintenance mode
6. Подождать 1 heartbeat

**Проверка:**
- [ ] 6.5.1 После `upload_enabled=false`: DataSyncService засыпает
- [ ] 6.5.2 Данные продолжают записываться в SQLite (Collector не останавливается)
- [ ] 6.5.3 Heartbeat продолжает ходить каждые 30s
- [ ] 6.5.4 После `upload_enabled=true`: DataSyncService просыпается и отправляет накопленное

---

## 6.6 Retention 72 часа

**Сценарий: Имитация старых данных**

1. Вставить в SQLite тестовые записи с `created_ts` = 4 дня назад
2. Запустить агент
3. Дождаться cleanup-цикла

**Проверка:**
- [ ] 6.6.1 Activity записи старше 72ч — удалены из `pending_activity`
- [ ] 6.6.2 Segment записи старше 72ч — удалены из `pending_segments` + файлы удалены
- [ ] 6.6.3 Записи младше 72ч — не тронуты
- [ ] 6.6.4 `PRAGMA incremental_vacuum` выполнен — SQLite file size уменьшился

---

## 6.7 Round-robin баланс

**Сценарий: Много activity + много видео одновременно**

1. Запустить агент, начать запись
2. Активно работать: много кликов, скроллов, переключений окон
3. Наблюдать за логами DataSyncService

**Проверка:**
- [ ] 6.7.1 В логах чередуется: "Sent activity batch" → "Sent video segment" → "Sent activity batch" → ...
- [ ] 6.7.2 Ни activity, ни video не "голодают" (оба типа отправляются)
- [ ] 6.7.3 При большом backlog — DataSyncService работает непрерывно (не ждёт 5s между round-robin)

---

## 6.8 Idempotent confirm

**Сценарий: Имитация потери ACK**

1. Агент отправляет confirm → сервер записывает в DB → response теряется (timeout)
2. Агент помечает сегмент как PENDING
3. На следующем round-robin: повторная отправка (presign → PUT → confirm)

**Проверка:**
- [ ] 6.8.1 Повторный confirm → 200 OK (не ошибка)
- [ ] 6.8.2 Сегмент на сервере — один (не дубликат)
- [ ] 6.8.3 session_stats корректны

---

## 6.9 Clipboard hash корреляция

**Сценарий:**

1. Скопировать текст из Chrome
2. Вставить текст в Word
3. Проверить на сервере

**Проверка:**
- [ ] 6.9.1 Два clipboard event'а с одинаковым `content_hash`
- [ ] 6.9.2 `SELECT * FROM user_input_events WHERE content_hash = '...'` — находит оба
- [ ] 6.9.3 Index используется (EXPLAIN ANALYZE)

---

## 6.10 Disk pressure

**Сценарий: Имитация заполнения диска**

1. Настроить `MaxBufferBytes = 50MB` (маленький буфер)
2. Записывать видео 10 минут (высокое качество)

**Проверка:**
- [ ] 6.10.1 SERVER_SIDE_DONE файлы удаляются первыми
- [ ] 6.10.2 NEW/PENDING файлы удаляются при disk pressure (с WARNING)
- [ ] 6.10.3 QUEUED файлы НЕ удаляются
- [ ] 6.10.4 `segments_evicted` в метриках > 0

---

## Чеклист фазы 6

- [ ] 6.1 Матрица совместимости (4 комбинации)
- [ ] 6.2 Happy path (5 мин работы)
- [ ] 6.3 Offline (2 мин + 1 час)
- [ ] 6.4 Crash recovery (kill -9)
- [ ] 6.5 Throttling (upload_enabled=false)
- [ ] 6.6 Retention 72 часа
- [ ] 6.7 Round-robin баланс
- [ ] 6.8 Idempotent confirm
- [ ] 6.9 Clipboard hash
- [ ] 6.10 Disk pressure
- [ ] Все тесты PASS → готов к деплою
