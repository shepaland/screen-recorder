# Фаза 7: Деплой и Rollback

> **Цель:** Безопасный деплой на test → prod. Rollback-план на каждом шаге.
>
> **Зависит от:** Фаза 6 (все тесты PASS)
> **Блокирует:** ничего

---

## 7.1 Порядок деплоя

```
Шаг 1: Сервер на test     ← backward-compatible, старые агенты не ломаются
Шаг 2: Smoke-тест сервера ← старый агент + новый сервер
Шаг 3: Агент v2 сборка    ← собрать на Windows machine
Шаг 4: Агент v2 на test   ← установить на тестовое устройство
Шаг 5: Полный тест         ← Фаза 6 чеклист на test-стейджинге
Шаг 6: Сервер на prod      ← ТОЛЬКО после подтверждения пользователя
Шаг 7: Агент v2 на prod    ← ТОЛЬКО после подтверждения пользователя
```

---

## 7.2 Шаг 1: Деплой сервера на test

### ingest-gateway

**Изменения в env/k8s:**
```yaml
# Убрать kafka-only mode:
KAFKA_CONFIRM_MODE: "sync"    # было: kafka-only

# Оставить dual-write:
KAFKA_DUAL_WRITE: "true"

# Добавить throttle:
THROTTLE_MAINTENANCE_MODE: "false"
```

**Деплой:**
```bash
# На shepaland-cloud
cd /home/shepelkina/screen-recorder/
# rsync обновлённых файлов
./mvnw package -DskipTests
docker build --no-cache -t prg-ingest-gateway:latest ...
sudo k3s ctr images remove docker.io/library/prg-ingest-gateway:latest
docker save prg-ingest-gateway:latest | sudo k3s ctr images import -
sudo k3s kubectl -n test-screen-record rollout restart deployment/ingest-gateway
```

**Flyway V40** выполнится автоматически при старте.

### control-plane

```bash
# Аналогично — пересобрать и задеплоить
# HeartbeatResponse с upload_enabled
```

**Проверка после деплоя:**
- [ ] `sudo k3s kubectl -n test-screen-record get pods` — все Running
- [ ] `curl /api/v1/ingest/presign` — endpoint доступен
- [ ] Heartbeat response содержит `upload_enabled: true`
- [ ] Flyway V40 applied: `\d user_input_events` содержит `content_hash`

### Rollback шага 1:
```bash
# Откатить deployment:
sudo k3s kubectl -n test-screen-record rollout undo deployment/ingest-gateway
sudo k3s kubectl -n test-screen-record rollout undo deployment/control-plane
# V40 миграция: content_hash — nullable, не ломает старый код
```

---

## 7.3 Шаг 2: Smoke-тест сервера со старым агентом

**Не устанавливать новый агент!** Проверить что старый агент работает с новым сервером.

- [ ] Heartbeat приходит → ответ 200 (с upload_enabled, агент игнорирует)
- [ ] Запись начинается (auto-start или команда)
- [ ] Сегменты presign → PUT → confirm → 200 OK (не 202)
- [ ] Focus intervals отправляются → 200 OK
- [ ] Input events отправляются → 200 OK
- [ ] Audit events отправляются → 200 OK
- [ ] Dashboard показывает данные

**Если что-то не работает → Rollback шага 1. Не продолжать.**

---

## 7.4 Шаг 3: Сборка агента v2

```bash
# На macOS: подготовить исходники
cd "/Users/alfa/Desktop/Альфа/Проекты/Запись экранов/screen-recorder"
COPYFILE_DISABLE=1 tar czf /tmp/kadero-agent-src.tar.gz \
  -C windows-agent-csharp/src/KaderoAgent .

# Загрузить на FTP
curl -T /tmp/kadero-agent-src.tar.gz ftp://192.168.1.38/upload/kadero-agent-src.tar.gz

# На Windows (192.168.1.135): сборка
# ... (стандартный workflow из MEMORY.md)
# Инкрементировать версию в KaderoAgent.csproj

# Результат: C:\kadero_install\KaderoAgentSetup.exe
```

---

## 7.5 Шаг 4: Установка агента v2 на тестовое устройство

**Устройство:** AIR911D (192.168.1.135), tenant "Тест 1"

1. Пользователь устанавливает через RDP (НЕ silent install!)
2. Проверить что агент запустился
3. Проверить SQLite миграцию (если были старые данные):
   ```
   # Через SSH:
   sqlite3 C:\screen-recorder-agent\agent.db ".tables"
   # Должны быть: pending_segments, pending_activity, agent_state
   # НЕ должно быть: audit_events (мигрирована)

   sqlite3 C:\screen-recorder-agent\agent.db "PRAGMA auto_vacuum"
   # Должно быть: 2 (INCREMENTAL)
   ```

---

## 7.6 Шаг 5: Полное тестирование на test-стейджинге

Выполнить **весь чеклист Фазы 6** на test-стейджинге.

Минимальный набор перед prod:
- [ ] Happy path (5 мин)
- [ ] Offline 2 мин → recovery
- [ ] Crash recovery (kill -9)
- [ ] Round-robin видно в логах
- [ ] Dashboard показывает activity данные

---

## 7.7 Шаг 6: Деплой сервера на prod

> **ТОЛЬКО после явного подтверждения пользователя!**

```bash
# На shepaland-cloud (сборка)
# ... собрать JAR + Docker image

# Перенести на shepaland-videocalls-test-srv
# ... docker save | ssh ... docker load
# ... sudo kubectl -n prod-screen-record rollout restart
```

**Проверка:**
- [ ] Все pods Running
- [ ] V40 applied
- [ ] Старые prod-агенты работают

### Rollback шага 6:
```bash
sudo kubectl -n prod-screen-record rollout undo deployment/ingest-gateway
sudo kubectl -n prod-screen-record rollout undo deployment/control-plane
```

---

## 7.8 Шаг 7: Агент v2 на prod

> **ТОЛЬКО после подтверждения что сервер на prod стабилен (24ч+)**

1. Собрать инсталлятор (если ещё не собран)
2. Пользователь устанавливает через RDP
3. Мониторить heartbeat metrics на сервере

---

## 7.9 Мониторинг после деплоя

**Первые 24 часа — усиленный мониторинг:**

```bash
# Проверка heartbeat metrics на сервере:
sudo k3s kubectl -n test-screen-record logs -f deployment/control-plane | grep "segments_pending\|activity_pending\|segments_evicted"

# Проверка что DataSyncService работает (на агенте):
# В логах агента: "Sent activity batch", "Sent video segment"

# Проверка SQLite (на агенте):
sqlite3 C:\screen-recorder-agent\agent.db \
  "SELECT status, COUNT(*) FROM pending_segments GROUP BY status"
sqlite3 C:\screen-recorder-agent\agent.db \
  "SELECT status, data_type, COUNT(*) FROM pending_activity GROUP BY status, data_type"
```

**Алерты (если настроен мониторинг):**
- `segments_evicted > 0` → потеря данных, расследовать
- `segments_pending > 50` → upload не справляется
- `activity_pending > 1000` → backlog activity
- `db_size_bytes > 50MB` → cleanup не работает

---

## 7.10 Rollback-план (полный)

| Что сломалось | Действие |
|---------------|----------|
| Сервер после Шага 1 | `rollout undo` ingest-gateway + control-plane |
| Агент v2 не работает | Переустановить старую версию агента через RDP |
| SQLite повреждена | Удалить `agent.db`, агент создаст новую при старте |
| Confirm возвращает ошибки | Вернуть `KAFKA_CONFIRM_MODE=kafka-only` (если нужно) |
| Throttling сломал upload | `THROTTLE_MAINTENANCE_MODE=false` |
| Данные не приходят на сервер | Проверить логи агента → DataSyncService. Проверить `server_available` |

**Критическое правило:** если rollback сервера не помогает — `sudo k3s kubectl -n test-screen-record rollout undo` для КАЖДОГО deployment по очереди.

---

## Чеклист фазы 7

- [ ] 7.2 Сервер на test — задеплоен, V40 applied
- [ ] 7.3 Smoke-тест: старый агент + новый сервер — OK
- [ ] 7.4 Агент v2 собран (инсталлятор готов)
- [ ] 7.5 Агент v2 установлен на тестовом устройстве
- [ ] 7.6 Полный тест Фазы 6 — PASS
- [ ] 7.7 Сервер на prod — ТОЛЬКО после подтверждения
- [ ] 7.8 Агент v2 на prod — ТОЛЬКО после подтверждения
- [ ] 7.9 Мониторинг 24ч — нет алертов
