# Пошаговая инструкция: тестирование Windows-агента записи экранов

Версия: 1.0 | Дата: 2026-03-02

---

## Содержание

1. [Предварительные требования](#1-предварительные-требования)
2. [Подготовка серверной инфраструктуры](#2-подготовка-серверной-инфраструктуры)
3. [Сборка Windows-агента](#3-сборка-windows-агента)
4. [Настройка агента](#4-настройка-агента)
5. [Создание токена регистрации устройства](#5-создание-токена-регистрации-устройства)
6. [Запуск агента и авторизация](#6-запуск-агента-и-авторизация)
7. [Проверка heartbeat и связи с сервером](#7-проверка-heartbeat-и-связи-с-сервером)
8. [Тестирование записи экрана](#8-тестирование-записи-экрана)
9. [Проверка загрузки сегментов на сервер](#9-проверка-загрузки-сегментов-на-сервер)
10. [Тестирование удалённых команд](#10-тестирование-удалённых-команд)
11. [Тестирование автообновления токена](#11-тестирование-автообновления-токена)
12. [Тестирование offline-буферизации](#12-тестирование-offline-буферизации)
13. [Тестирование Windows Service режима](#13-тестирование-windows-service-режима)
14. [Чек-лист приёмочного тестирования](#14-чек-лист-приёмочного-тестирования)
15. [Диагностика проблем](#15-диагностика-проблем)

---

## 1. Предварительные требования

### На Windows-машине (тестовый ПК)

| Компонент | Версия | Проверка |
|-----------|--------|----------|
| Windows | 10/11 x64 | `winver` |
| Java (JDK) | 21+ | `java -version` |
| FFmpeg | 6.x+ | `ffmpeg -version` |
| Сетевой доступ | до сервера (порт 443) | `curl https://videocalls.shepaland.ru/health` |

**Установка FFmpeg (если не установлен):**
```powershell
# Вариант 1: через winget
winget install Gyan.FFmpeg

# Вариант 2: через chocolatey
choco install ffmpeg

# Вариант 3: скачать вручную
# https://www.gyan.dev/ffmpeg/builds/ → ffmpeg-release-essentials.zip
# Распаковать в C:\ffmpeg, добавить C:\ffmpeg\bin в PATH
```

**Проверка FFmpeg:**
```powershell
ffmpeg -version
# Должен вывести: ffmpeg version 6.x или 7.x
# Убедиться что есть: libx264, gdigrab
ffmpeg -f gdigrab -list_devices true -i dummy 2>&1 | Select-String "gdigrab"
```

### На сервере

| Компонент | Статус | Проверка |
|-----------|--------|----------|
| auth-service | Running | `curl https://videocalls.shepaland.ru/api/v1/auth/login` → 4xx |
| control-plane | Running | (доступ внутри кластера) |
| ingest-gateway | Running | (доступ внутри кластера) |
| MinIO | Running | (доступ внутри кластера) |
| Ingress-маршруты для агента | **Требуется настройка** | См. раздел 2 |

---

## 2. Подготовка серверной инфраструктуры

### 2.1. Создание ingress-маршрутов для агентов

> **ВАЖНО:** Сейчас только web-dashboard доступен извне (`videocalls.shepaland.ru`).
> nginx проксирует `/api/` только на auth-service. Агенту нужен доступ к control-plane и ingest-gateway.

**Вариант A — добавить proxy_pass в nginx.conf (рекомендуется):**

Отредактировать `deploy/docker/web-dashboard/nginx.conf`, добавив:

```nginx
    # Proxy control-plane API requests (для агентов)
    location /api/cp/ {
        proxy_pass http://control-plane:8080/api/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    # Proxy ingest-gateway API requests (для загрузки сегментов)
    location /api/ingest/ {
        proxy_pass http://ingest-gateway:8084/api/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        client_max_body_size 50m;  # Для загрузки видеосегментов
    }

    # Proxy для presigned URL MinIO (агент загружает сегменты напрямую в MinIO)
    location /s3/ {
        proxy_pass http://minio:9000/;
        proxy_set_header Host $host;
        client_max_body_size 50m;
    }
```

**Вариант B — создать отдельные IngressRoute для Traefik:**

Создать файлы `deploy/k8s/control-plane/ingress.yaml` и `deploy/k8s/ingest-gateway/ingress.yaml` с маршрутами по path prefix.

### 2.2. Пересборка и деплой web-dashboard (после изменения nginx.conf)

```bash
# Сборка
ssh shepaland-videcalls-test-srv "cd ~/screen-recorder && \
  docker build -t prg-web-dashboard:latest -f deploy/docker/web-dashboard/Dockerfile ."

# Деплой
ssh shepaland-videcalls-test-srv "sudo k3s ctr images remove docker.io/library/prg-web-dashboard:latest 2>/dev/null; \
  docker save prg-web-dashboard:latest | sudo k3s ctr images import -"
ssh shepaland-videcalls-test-srv "sudo kubectl -n test-screen-record rollout restart deployment/web-dashboard"
```

### 2.3. Настройка auth-service serverConfig

Убедиться, что auth-service при device-login возвращает правильные внешние URL.
В ConfigMap `auth-service-config`:

```yaml
INGEST_BASE_URL: "https://videocalls.shepaland.ru/api/ingest/v1/ingest"
CONTROL_PLANE_BASE_URL: "https://videocalls.shepaland.ru/api/cp/v1/devices"
```

> Значения зависят от того, как настроен proxy path в nginx.
> Если используется Вариант A (nginx proxy), то URL формируется как `https://domain/api/cp/v1/...`

### 2.4. Проверка доступности API для агента

```powershell
# С Windows-машины:

# Auth endpoint (через nginx /api/)
curl -s -o NUL -w "%{http_code}" https://videocalls.shepaland.ru/api/v1/auth/device-login
# Ожидание: 400 или 405 (метод/тело не то, но маршрут доступен)

# Control-plane endpoint
curl -s -o NUL -w "%{http_code}" https://videocalls.shepaland.ru/api/cp/actuator/health
# Ожидание: 200

# Ingest endpoint
curl -s -o NUL -w "%{http_code}" https://videocalls.shepaland.ru/api/ingest/actuator/health
# Ожидание: 200
```

---

## 3. Сборка Windows-агента

### 3.1. Сборка на билд-сервере

```bash
# С локальной машины — rsync проекта на сервер
rsync -avz --delete --exclude='.git' --exclude='node_modules' --exclude='target' --exclude='dist' \
  "/Users/alfa/Desktop/Альфа/Проекты/Запись экранов/screen-recorder/" \
  shepaland-videcalls-test-srv:~/screen-recorder/

# Сборка JAR на сервере
ssh shepaland-videcalls-test-srv "cd ~/screen-recorder/windows-agent && \
  MAVEN_OPTS='-Xms128m -Xmx512m' ../mvnw package -DskipTests -q"

# Проверка артефакта
ssh shepaland-videcalls-test-srv "ls -lh ~/screen-recorder/windows-agent/target/windows-agent-1.0.0.jar"
```

### 3.2. Копирование JAR на Windows-машину

```powershell
# На Windows-машине (PowerShell):
mkdir C:\PRG-Agent -Force
scp shepaland@158.160.208.126:~/screen-recorder/windows-agent/target/windows-agent-1.0.0.jar C:\PRG-Agent\agent.jar
scp shepaland@158.160.208.126:~/screen-recorder/windows-agent/src/main/resources/agent.properties C:\PRG-Agent\config\agent.properties
```

> **Альтернатива:** Если нет прямого доступа к серверу с Windows, скачать JAR через промежуточную машину или собрать локально:
> ```powershell
> cd C:\path\to\screen-recorder\windows-agent
> .\mvnw.cmd package -DskipTests
> ```

### 3.3. Проверка запуска

```powershell
cd C:\PRG-Agent
java -jar agent.jar --version
# Ожидание: "PRG Screen Recorder Agent v1.0.0" или аналогичная версия
```

---

## 4. Настройка агента

### 4.1. Редактирование agent.properties

Открыть `C:\PRG-Agent\config\agent.properties` и задать URL сервера:

```properties
# Основной URL сервера
server.base.url=https://videocalls.shepaland.ru

# Auth API (через nginx proxy /api/)
server.auth.url=https://videocalls.shepaland.ru/api/v1/auth

# Control-plane API (через nginx proxy /api/cp/)
server.control.url=https://videocalls.shepaland.ru/api/cp/v1/devices

# Ingest API (через nginx proxy /api/ingest/)
server.ingest.url=https://videocalls.shepaland.ru/api/ingest/v1/ingest

# Рабочие директории
agent.data.dir=C:/PRG-Agent/data
agent.log.dir=C:/PRG-Agent/logs
agent.segments.dir=C:/PRG-Agent/segments

# Буфер и загрузка
agent.max.buffer.mb=2048
agent.upload.threads=2
agent.upload.retry.max=3

# Параметры захвата (будут перезаписаны сервером после авторизации)
agent.capture.fps=5
agent.capture.segment.duration.sec=10
agent.capture.quality=medium

# Путь к FFmpeg (если не в PATH)
# agent.ffmpeg.path=C:/ffmpeg/bin/ffmpeg.exe
```

### 4.2. Создание рабочих директорий

```powershell
mkdir C:\PRG-Agent\data -Force
mkdir C:\PRG-Agent\logs -Force
mkdir C:\PRG-Agent\segments -Force
```

---

## 5. Создание токена регистрации устройства

> Токен регистрации — одноразовый (или многоразовый) код, привязанный к тенанту.
> Создаётся администратором через API или веб-интерфейс.

### 5.1. Через API (curl)

```bash
# 1. Получить JWT токен администратора
TOKEN=$(curl -s -X POST https://videocalls.shepaland.ru/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"superadmin","password":"<ПАРОЛЬ>"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])")

echo "Token: $TOKEN"

# 2. Получить tenant_id
TENANT_ID=$(curl -s https://videocalls.shepaland.ru/api/v1/tenants \
  -H "Authorization: Bearer $TOKEN" \
  | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['content'][0]['id'] if d.get('content') else 'NOT_FOUND')")

echo "Tenant ID: $TENANT_ID"

# 3. Создать токен регистрации
curl -s -X POST "https://videocalls.shepaland.ru/api/v1/tenants/$TENANT_ID/device-registration-tokens" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "name": "Тестовый токен для Windows-агента",
    "max_uses": 10,
    "expires_at": "2026-12-31T23:59:59Z"
  }' | python3 -m json.tool
```

**Ожидаемый ответ:**
```json
{
  "id": "uuid-xxx",
  "token": "raw-token-value-SAVE-THIS",
  "name": "Тестовый токен для Windows-агента",
  "max_uses": 10,
  "current_uses": 0,
  "is_active": true,
  "expires_at": "2026-12-31T23:59:59Z"
}
```

> **СОХРАНИТЕ значение `token`** — оно потребуется при авторизации агента. Показывается только один раз.

### 5.2. Через веб-интерфейс

1. Открыть `https://videocalls.shepaland.ru`
2. Войти как superadmin (или tenant admin / manager)
3. Перейти в раздел **Устройства** → **Токены регистрации**
4. Нажать **Создать токен**
5. Заполнить:
   - Имя: `Тестовый токен`
   - Максимум использований: 10
   - Срок действия: (указать дату)
6. Скопировать и сохранить сгенерированный токен

---

## 6. Запуск агента и авторизация

### 6.1. Запуск в интерактивном режиме

```powershell
cd C:\PRG-Agent
java -jar agent.jar
```

**Ожидаемое поведение:**
- В системном трее (рядом с часами) появится иконка — **красная** (NOT_AUTHENTICATED)
- Tooltip: "PRG Screen Recorder — Требуется вход"

### 6.2. Авторизация устройства

1. **Двойной клик** по иконке в трее → откроется окно конфигурации
2. Перейти на вкладку **Login** (если не на ней)
3. Заполнить поля:

| Поле | Значение | Описание |
|------|----------|----------|
| Registration Token | `raw-token-value...` | Токен из шага 5 |
| Username | `superadmin` | Или другой пользователь с ролью TENANT_ADMIN / MANAGER |
| Password | `<пароль>` | Пароль пользователя |

4. Нажать **Login**

**Ожидаемый результат:**
- Иконка в трее меняется на **жёлтую** (ONLINE)
- Tooltip: "PRG Screen Recorder — Подключён"
- В окне статуса отображаются:
  - Device ID: `uuid-xxx`
  - Tenant: `prg-platform`
  - Status: `ONLINE`

### 6.3. Проверка на сервере

```bash
# Проверить, что устройство зарегистрировано
TOKEN=$(curl -s -X POST https://videocalls.shepaland.ru/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"superadmin","password":"<ПАРОЛЬ>"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])")

curl -s https://videocalls.shepaland.ru/api/v1/devices \
  -H "Authorization: Bearer $TOKEN" \
  | python3 -m json.tool
```

**Ожидание:** в списке устройств появится новое устройство со статусом `online`.

### 6.4. Проверка через веб-интерфейс

1. Открыть `https://videocalls.shepaland.ru` → раздел **Устройства**
2. Убедиться, что устройство отображается:
   - Hostname: имя Windows-ПК
   - Status: `online`
   - IP Address: IP агента
   - Agent Version: `1.0.0`

---

## 7. Проверка heartbeat и связи с сервером

### 7.1. Мониторинг heartbeat на стороне агента

```powershell
# Просмотр логов в реальном времени
Get-Content C:\PRG-Agent\logs\agent.log -Wait -Tail 50 | Select-String "heartbeat|Heartbeat"
```

**Ожидание:** каждые ~30 секунд строки вида:
```
2026-03-02 12:00:30 [heartbeat-scheduler] INFO HeartbeatScheduler - Heartbeat sent: status=online, next=30s
2026-03-02 12:01:00 [heartbeat-scheduler] INFO HeartbeatScheduler - Heartbeat sent: status=online, next=30s
```

### 7.2. Мониторинг heartbeat на стороне сервера

```bash
# Логи control-plane
ssh shepaland-videcalls-test-srv "sudo kubectl -n test-screen-record logs -f deployment/control-plane --tail=20" \
  | grep -i heartbeat
```

**Ожидание:** записи о получении heartbeat от устройства.

### 7.3. Проверка метрик устройства в БД

```bash
ssh shepaland-videcalls-test-srv "sudo -u postgres psql -d prg_test -c \
  \"SELECT id, hostname, status, ip_address, agent_version, last_heartbeat_ts FROM devices ORDER BY last_heartbeat_ts DESC LIMIT 5;\""
```

**Ожидание:** `last_heartbeat_ts` обновляется каждые 30 секунд.

---

## 8. Тестирование записи экрана

### 8.1. Запуск записи из GUI агента

1. Двойной клик по иконке в трее → окно статуса
2. Нажать кнопку **Start Recording**

**Ожидаемое поведение:**
- Иконка меняется на **зелёную** (RECORDING)
- Tooltip: "PRG Screen Recorder — Запись ведётся"
- В окне статуса:
  - Status: `RECORDING`
  - Timer: отсчёт времени записи
  - Segments queued: растёт

### 8.2. Проверка создания файлов сегментов

```powershell
# Проверить директорию сегментов
ls C:\PRG-Agent\segments\ -Recurse
# Ожидание: папка с session_id, внутри файлы .mp4
# Каждые 10 секунд появляется новый файл (~100-500 KB)

# Размер сегмента
Get-ChildItem C:\PRG-Agent\segments\ -Recurse -Filter *.mp4 | Select-Object Name, Length, CreationTime
```

**Ожидание:** файлы .mp4 размером 50-500 KB каждый, новый каждые 10 секунд.

### 8.3. Проверка валидности видео

```powershell
# Взять любой сегмент и проверить FFprobe
$segment = (Get-ChildItem C:\PRG-Agent\segments\ -Recurse -Filter *.mp4 | Select-Object -First 1).FullName
ffprobe -v error -show_format -show_streams $segment
```

**Ожидание:**
```
codec_name=h264
codec_type=video
width=1920       # Или разрешение вашего экрана
height=1080
r_frame_rate=5/1
```

### 8.4. Остановка записи

1. В окне статуса нажать **Stop Recording**
2. Иконка возвращается на **жёлтую** (ONLINE)

### 8.5. Проверка на сервере

```bash
# Проверить сессии записи в БД (ingest-gateway)
ssh shepaland-videcalls-test-srv "sudo -u postgres psql -d prg_test -c \
  \"SELECT id, device_id, status, segment_count, total_bytes, started_ts, ended_ts FROM recording_sessions ORDER BY started_ts DESC LIMIT 5;\""
```

---

## 9. Проверка загрузки сегментов на сервер

### 9.1. Логи загрузки на агенте

```powershell
Get-Content C:\PRG-Agent\logs\agent.log -Wait -Tail 100 | Select-String "upload|presign|confirm|Upload"
```

**Ожидание:** для каждого сегмента:
```
INFO SegmentUploader - Presigning segment: session=xxx seq=0 size=256000
INFO SegmentUploader - Uploading segment to presigned URL...
INFO SegmentUploader - Upload complete, confirming...
INFO SegmentUploader - Segment confirmed: id=seg-xxx status=confirmed
```

### 9.2. Проверка сегментов в PostgreSQL

```bash
ssh shepaland-videcalls-test-srv "sudo -u postgres psql -d prg_test -c \
  \"SELECT id, session_id, sequence_num, s3_key, size_bytes, status, checksum_sha256
   FROM segments
   ORDER BY created_ts DESC LIMIT 10;\""
```

**Ожидание:** сегменты со статусом `confirmed`.

### 9.3. Проверка файлов в MinIO

```bash
# Из пода ingest-gateway или через mc
ssh shepaland-videcalls-test-srv "sudo kubectl -n test-screen-record exec deployment/minio -- \
  ls /data/prg-segments/ -la"

# Или через MinIO client
ssh shepaland-videcalls-test-srv "sudo kubectl -n test-screen-record run mc-temp --rm -i --image=minio/mc --restart=Never -- \
  sh -c 'mc alias set local http://minio:9000 minioadmin minioadmin && mc ls local/prg-segments/ --recursive | tail -20'"
```

**Ожидание:** файлы в структуре `tenant_id/device_id/session_id/sequence_num.mp4`

### 9.4. Проверка целостности (checksum)

```bash
# Сравнить checksum в БД с реальным файлом в MinIO
ssh shepaland-videcalls-test-srv "sudo -u postgres psql -d prg_test -c \
  \"SELECT s3_key, checksum_sha256 FROM segments ORDER BY created_ts DESC LIMIT 1;\""
# Записать s3_key и checksum

# Скачать файл из MinIO и вычислить checksum
ssh shepaland-videcalls-test-srv "sudo kubectl -n test-screen-record run mc-verify --rm -i --image=minio/mc --restart=Never -- \
  sh -c 'mc alias set local http://minio:9000 minioadmin minioadmin && mc cat local/prg-segments/<S3_KEY> | sha256sum'"
```

**Ожидание:** checksums совпадают.

---

## 10. Тестирование удалённых команд

### 10.1. Команда START_RECORDING

```bash
# Получить token и device_id
TOKEN=$(curl -s -X POST https://videocalls.shepaland.ru/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"superadmin","password":"<ПАРОЛЬ>"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])")

DEVICE_ID="<device-id-из-шага-6>"

# Отправить команду начать запись
curl -s -X POST "https://videocalls.shepaland.ru/api/cp/v1/devices/$DEVICE_ID/commands" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"command_type":"START_RECORDING","payload":{}}' \
  | python3 -m json.tool
```

**Ожидание на агенте:**
- В течение 30 секунд (следующий heartbeat) агент получит команду
- Иконка станет **зелёной**
- В логах: `CommandHandler - Processing command: START_RECORDING`

### 10.2. Команда STOP_RECORDING

```bash
curl -s -X POST "https://videocalls.shepaland.ru/api/cp/v1/devices/$DEVICE_ID/commands" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"command_type":"STOP_RECORDING","payload":{}}' \
  | python3 -m json.tool
```

**Ожидание:** запись останавливается, иконка **жёлтая**.

### 10.3. Команда UPDATE_SETTINGS

```bash
curl -s -X POST "https://videocalls.shepaland.ru/api/cp/v1/devices/$DEVICE_ID/commands" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"command_type":"UPDATE_SETTINGS","payload":{"capture_fps":10,"segment_duration_sec":5,"quality":"high"}}' \
  | python3 -m json.tool
```

**Ожидание:** параметры захвата обновлены (видно в логах агента).

### 10.4. Проверка acknowledgment команд

```bash
# Проверить статус команды
curl -s "https://videocalls.shepaland.ru/api/cp/v1/devices/$DEVICE_ID/commands?status=acknowledged" \
  -H "Authorization: Bearer $TOKEN" \
  | python3 -m json.tool
```

**Ожидание:** команды в статусе `acknowledged` с `result: {"message":"OK"}`.

---

## 11. Тестирование автообновления токена

### 11.1. Принудительное истечение access token

Access token живёт 15 минут (900 секунд). Для теста:

1. Авторизовать агент (шаг 6)
2. Подождать ~15 минут
3. Наблюдать за логами:

```powershell
Get-Content C:\PRG-Agent\logs\agent.log -Wait -Tail 50 | Select-String "refresh|token|Token"
```

**Ожидание:**
```
INFO AuthManager - Access token expiring soon, refreshing...
INFO AuthManager - Token refreshed successfully
```

### 11.2. Проверка, что heartbeat продолжает работать

После обновления токена heartbeat должен продолжаться без сбоев:

```powershell
Get-Content C:\PRG-Agent\logs\agent.log -Wait -Tail 50 | Select-String "heartbeat"
```

### 11.3. Перезапуск агента (проверка персистентности)

1. Закрыть агент (правый клик на иконку → Exit)
2. Запустить снова: `java -jar agent.jar`

**Ожидание:**
- Агент автоматически восстанавливает сессию (без повторного ввода credentials)
- Иконка сразу **жёлтая** (ONLINE), не красная
- В логах: `INFO AuthManager - Restoring session from stored credentials`

---

## 12. Тестирование offline-буферизации

### 12.1. Имитация потери связи

1. Запустить запись (шаг 8 или команда START_RECORDING)
2. **Отключить сеть** на Windows-машине (или заблокировать доступ к серверу через firewall):

```powershell
# Блокировка доступа к серверу
New-NetFirewallRule -DisplayName "Block PRG Server" -Direction Outbound -RemoteAddress 158.160.208.126 -Action Block
```

3. Подождать 1-2 минуты (сегменты продолжают создаваться)

### 12.2. Проверка локального буфера

```powershell
# Проверить SQLite базу
# Требуется sqlite3.exe или можно посмотреть размер файла
ls C:\PRG-Agent\data\agent.db

# Проверить очередь сегментов на диске
ls C:\PRG-Agent\segments\ -Recurse | Measure-Object
# Ожидание: файлы накапливаются, не удаляются

# Логи
Get-Content C:\PRG-Agent\logs\agent.log -Tail 20 | Select-String "retry|offline|buffer|failed"
```

**Ожидание:** в логах:
```
WARN SegmentUploader - Upload failed, saving to local buffer: connection refused
INFO UploadQueue - Segment saved to pending: session=xxx seq=5
```

### 12.3. Восстановление связи

```powershell
# Снять блокировку
Remove-NetFirewallRule -DisplayName "Block PRG Server"
```

**Ожидание:**
- Агент автоматически обнаруживает восстановление связи (на следующем heartbeat)
- Начинается отправка буферизованных сегментов
- В логах:
```
INFO UploadQueue - Retrying pending segments: count=12
INFO SegmentUploader - Pending segment uploaded: session=xxx seq=3
INFO SegmentUploader - Pending segment uploaded: session=xxx seq=4
...
INFO UploadQueue - All pending segments uploaded
```

### 12.4. Проверка на сервере

```bash
# Убедиться, что все сегменты доставлены (нет пропусков в sequence_num)
ssh shepaland-videcalls-test-srv "sudo -u postgres psql -d prg_test -c \
  \"SELECT session_id, MIN(sequence_num) as min_seq, MAX(sequence_num) as max_seq, COUNT(*) as total
   FROM segments WHERE session_id = '<SESSION_ID>'
   GROUP BY session_id;\""
```

**Ожидание:** `total = max_seq - min_seq + 1` (нет пропусков).

---

## 13. Тестирование Windows Service режима

### 13.1. Установка как службы Windows

```powershell
# Остановить интерактивный экземпляр, если запущен

# Зарегистрировать службу
sc.exe create PRGScreenRecorder binPath= "\"C:\Program Files\Java\jdk-21\bin\java.exe\" -jar \"C:\PRG-Agent\agent.jar\" --service" DisplayName= "PRG Screen Recorder" start= auto

# Настроить авто-перезапуск при сбоях
sc.exe failure PRGScreenRecorder reset= 86400 actions= restart/60000/restart/120000/restart/300000

# Запустить
sc.exe start PRGScreenRecorder
```

### 13.2. Проверка работы службы

```powershell
# Статус
sc.exe query PRGScreenRecorder

# Ожидание: STATE = RUNNING

# Логи (служба пишет в файл, не в GUI)
Get-Content C:\PRG-Agent\logs\agent.log -Wait -Tail 20
```

### 13.3. Удаление службы (после теста)

```powershell
sc.exe stop PRGScreenRecorder
sc.exe delete PRGScreenRecorder
```

---

## 14. Чек-лист приёмочного тестирования

| # | Тест-кейс | Ожидание | Результат |
|---|-----------|----------|-----------|
| 1 | Запуск агента в интерактивном режиме | Иконка в трее (красная) | |
| 2 | Авторизация с валидными credentials | Иконка жёлтая, Device ID отображается | |
| 3 | Авторизация с неверным паролем | Ошибка в GUI, иконка остаётся красной | |
| 4 | Авторизация с неактивным токеном | Ошибка "Registration token is deactivated" | |
| 5 | Heartbeat каждые 30 сек | Логи подтверждают, last_heartbeat_ts обновляется | |
| 6 | Устройство видно в веб-интерфейсе | Список устройств, статус online | |
| 7 | Запуск записи из GUI | Иконка зелёная, файлы .mp4 создаются | |
| 8 | Остановка записи из GUI | Иконка жёлтая, FFmpeg процесс завершён | |
| 9 | Сегменты загружаются на сервер | Записи в таблице segments, файлы в MinIO | |
| 10 | Checksum сегментов совпадает | SHA-256 в БД = SHA-256 файла в MinIO | |
| 11 | Удалённая команда START_RECORDING | Запись начинается после heartbeat | |
| 12 | Удалённая команда STOP_RECORDING | Запись останавливается | |
| 13 | Удалённая команда UPDATE_SETTINGS | Параметры обновляются в логах | |
| 14 | Команды подтверждаются (ack) | status=acknowledged в БД | |
| 15 | Token auto-refresh (через 15 мин) | Новый access_token, heartbeat продолжается | |
| 16 | Перезапуск агента — сессия сохраняется | Автоматический вход без ввода credentials | |
| 17 | Offline-буферизация | Сегменты сохраняются локально при потере связи | |
| 18 | Восстановление связи → досылка | Буферизованные сегменты отправляются автоматически | |
| 19 | Нет пропусков в sequence_num | total = max_seq - min_seq + 1 | |
| 20 | Видео корректно (FFprobe) | H.264, заданный FPS, разрешение экрана | |
| 21 | Windows Service режим | Служба стартует, записывает, heartbeat работает | |
| 22 | Метрики в heartbeat | CPU, memory, disk, queue в ответе heartbeat | |

---

## 15. Диагностика проблем

### Проблема: Агент не может подключиться к серверу

```powershell
# 1. Проверить DNS
nslookup videocalls.shepaland.ru

# 2. Проверить доступность порта
Test-NetConnection videocalls.shepaland.ru -Port 443

# 3. Проверить TLS
curl -v https://videocalls.shepaland.ru/health

# 4. Проверить firewall
Get-NetFirewallRule | Where-Object { $_.Direction -eq 'Outbound' -and $_.Action -eq 'Block' }
```

### Проблема: 401 при device-login

```powershell
# Проверить, что токен регистрации активен
# Проверить, что пользователь имеет роль TENANT_ADMIN или MANAGER
# Проверить логи auth-service:
ssh shepaland-videcalls-test-srv "sudo kubectl -n test-screen-record logs deployment/auth-service --tail=50 | grep -i -E 'device|login|error'"
```

### Проблема: FFmpeg не найден

```powershell
# Проверить PATH
$env:PATH -split ';' | Select-String ffmpeg
ffmpeg -version

# Указать путь вручную в agent.properties:
# agent.ffmpeg.path=C:/ffmpeg/bin/ffmpeg.exe
```

### Проблема: Сегменты не загружаются

```powershell
# Логи агента
Get-Content C:\PRG-Agent\logs\agent.log -Tail 100 | Select-String "upload|error|fail"

# Проверить presign endpoint
curl -s -o NUL -w "%{http_code}" https://videocalls.shepaland.ru/api/ingest/actuator/health

# Проверить MinIO доступность
ssh shepaland-videcalls-test-srv "sudo kubectl -n test-screen-record exec deployment/ingest-gateway -- wget -q -O - http://minio:9000/minio/health/ready"
```

### Проблема: Большое потребление CPU/памяти

```powershell
# Проверить процесс FFmpeg
Get-Process ffmpeg | Select-Object CPU, WorkingSet64, Id

# Снизить нагрузку — уменьшить FPS или качество в agent.properties:
# agent.capture.fps=3
# agent.capture.quality=low
```

### Полезные команды для отладки

```powershell
# Последние ошибки в логах
Get-Content C:\PRG-Agent\logs\agent.log -Tail 200 | Select-String "ERROR|WARN|Exception"

# Размер буфера сегментов
Get-ChildItem C:\PRG-Agent\segments\ -Recurse | Measure-Object -Property Length -Sum | Select-Object @{N='SizeMB';E={[math]::Round($_.Sum/1MB,2)}}

# Количество ожидающих сегментов
Get-ChildItem C:\PRG-Agent\segments\ -Recurse -Filter *.mp4 | Measure-Object | Select-Object Count

# Убить зависший FFmpeg
Stop-Process -Name ffmpeg -Force

# Очистить все данные агента (полный сброс)
Remove-Item C:\PRG-Agent\data -Recurse -Force
Remove-Item C:\PRG-Agent\segments -Recurse -Force
Remove-Item C:\PRG-Agent\logs -Recurse -Force
# + удалить ~/.prg-agent/credentials.enc
Remove-Item $env:USERPROFILE\.prg-agent -Recurse -Force
```

---

## Приложение A: Схема взаимодействия компонентов

```
Windows Agent                          Server (k8s)
============                          =============

[Login Panel]
     |
     | POST /api/v1/auth/device-login
     |──────────────────────────────────► [auth-service:8081]
     |◄──────────────────────────────────  JWT + device_id + serverConfig
     |
[Heartbeat Loop, каждые 30 сек]
     |
     | PUT /api/v1/devices/{id}/heartbeat
     |──────────────────────────────────► [control-plane:8080]
     |◄──────────────────────────────────  pending_commands[]
     |
     | PUT /api/v1/devices/commands/{id}/ack
     |──────────────────────────────────► [control-plane:8080]
     |
[Screen Capture - FFmpeg]
     |
     | Создаёт .mp4 сегменты (10 сек)
     ▼
[Upload Queue]
     |
     | POST /api/v1/ingest/presign
     |──────────────────────────────────► [ingest-gateway:8084]
     |◄──────────────────────────────────  presigned_url
     |
     | PUT presigned_url (файл)
     |──────────────────────────────────► [MinIO:9000]
     |
     | POST /api/v1/ingest/confirm
     |──────────────────────────────────► [ingest-gateway:8084]
     |◄──────────────────────────────────  confirmed + session_stats
```

## Приложение B: Структура данных на диске агента

```
C:\PRG-Agent\
├── agent.jar                         # Исполняемый файл
├── config\
│   └── agent.properties              # Конфигурация
├── data\
│   └── agent.db                      # SQLite (pending segments, state)
├── logs\
│   ├── agent.log                     # Текущий лог
│   └── agent.2026-03-02.log.gz       # Архивные логи
└── segments\
    └── {session-id}\
        ├── {session-id}_00000.mp4    # Сегмент 0 (10 сек)
        ├── {session-id}_00001.mp4    # Сегмент 1
        └── ...

~/.prg-agent\
└── credentials.enc                   # Зашифрованные credentials (AES-256-GCM)
```
