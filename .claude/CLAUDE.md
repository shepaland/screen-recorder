# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

# Screen Recorder (Кадеро)

Мультитенантная платформа записи экранов операторов контактного центра.
H.264 fMP4 запись → сегментация → S3 хранение → HLS воспроизведение → полнотекстовый поиск.

## Стек

- **Backend:** Java 21, Spring Boot 3.x, Spring Security (JWT), Spring Data JPA, Spring WebFlux (ingest), Flyway (миграции)
- **Frontend:** React 18, Vite, TypeScript, Tailwind CSS, Axios, React Router v6
- **macOS Agent:** Swift 5.10, ScreenCaptureKit, AVAssetWriter, SQLite
- **Windows Agent:** C# .NET 8, Inno Setup (инсталлятор), SQLite
- **Инфраструктура:** PostgreSQL 16 (на хосте, не в k8s), MinIO (S3), Apache Kafka 3.7 (KRaft), OpenSearch 2.12, k3s, Traefik
- **Мониторинг:** Prometheus, Grafana, Loki, Tempo, AlertManager

## Сервисы и порты

| Сервис | Порт | Назначение |
|--------|------|------------|
| auth-service | 8081 | JWT, RBAC (6 ролей, 27 пермишенов), ABAC, аудит |
| control-plane | 8080 | Устройства, политики записи, команды агентам (Kafka), вебхуки |
| ingest-gateway | 8084 | Приём видеосегментов: presign → upload → confirm → MinIO |
| playback-service | 8082 | HLS M3U8 плейлисты, проксирование сегментов из MinIO |
| search-service | 8083 | Kafka consumer → OpenSearch индексация, полнотекстовый поиск |
| web-dashboard | 80 | React SPA, nginx |

## Git

- **Remote:** `git@github.com:shepaland/screen-recorder.git`
- После завершения разработки фичи/фикса — **всегда коммитить и пушить** изменения в remote.
- Ветки: `feature/*`, `fix/*`, `hotfix/*`. Мержить в `main` через Pull Request.
- При работе скиллов `/pipeline`, `/implement`, `/deploy` — коммит и push обязателен в конце.

## Команды

```bash
# Java-сервисы (из директории сервиса, mvnw внутри каждого сервиса)
cd SERVICE && ./mvnw clean compile    # компиляция
cd SERVICE && ./mvnw test             # unit-тесты
cd SERVICE && ./mvnw verify           # integration-тесты
cd SERVICE && ./mvnw package -DskipTests  # сборка JAR

# Frontend (web-dashboard/)
npm install
npm run dev
npm run build
npm run lint
npm run test

# Docker-сборка
docker build -t prg-<service>:latest -f deploy/docker/<service>/Dockerfile .

# k8s деплой (указать namespace стейджинга)
kubectl -n <namespace> apply -f deploy/k8s/
kubectl -n <namespace> rollout restart deployment/<service>
kubectl -n <namespace> logs -f deployment/<service>

# macOS Agent
swift build && swift test
```

## Сборка Windows Agent

- **Сборка**: на Windows-машине `192.168.1.135` (логин: `shepaland` / `#6TY0N0d`)
- **Обмен файлами**: через FTP `192.168.1.38` (анонимный доступ, без пароля)
- **Инсталлятор**: результат сборки размещается в `C:\kadero_install\` (только инсталлятор, никаких других файлов!)
- **Workflow**: Mac → tarball → FTP upload → Windows скачивает с FTP → dotnet publish → Inno Setup → `C:\kadero_install\KaderoAgentSetup.exe`

## Развёртывание


| Среда | Сервер | Namespace | URL | БД |
|-------|--------|-----------|-----|----|
| test | shepaland-cloud | `test-screen-record` | `https://services-test.shepaland.ru/screenrecorder` | `prg_test` |


- **shepaland-cloud** (158.160.222.120): k3s, cert-manager + ClusterIssuer, PostgreSQL на хосте
- **shepaland-videocalls-test-srv** (158.160.130.90): k3s, Traefik ACME, PostgreSQL на хосте (172.17.0.1:5432)

### Path-based routing

Оба стейджинга используют path `/screenrecorder`. Traefik StripPrefix middleware убирает `/screenrecorder` перед пересылкой в nginx. Vite `base: "/screenrecorder/"`, React Router `basename="/screenrecorder"`.

### Пайплайн деплоя

!!!! СБОРКА ВСЕХ СЕРВЕРНЫХ КОМПОНЕНТ ПРОИЗВОДИТЬ НА shepaland-cloud !!!!

1. **test** — сборка + деплой на shepaland-cloud. Smoke-тесты, функциональное/интеграционное тестирование.
2. **prod** — деплой на shepaland-videocalls-test-srv ТОЛЬКО после: все тест-кейсы passed + явное подтверждение от пользователя. Никогда не деплоить на prod без спроса.

### Правила сборки и деплоя

- **Сборка Java-сервисов и Docker-образов** выполняется ТОЛЬКО на сервере стейджинга (shepaland-cloud), НЕ локально.
- **Проверка после деплоя** на test-стейджинге — использовать учётную запись `maksim` / `#6TY0N0d` (tenant "Тест 1").
- **Учётные данные для проверки** описаны в этом файле (секция выше) и в memory-файлах проекта.

## Архитектура (ключевое)

Data Plane (видеопоток) отделён от Control Plane (команды, конфигурация). Все сервисы stateless.

**Ingest flow:**
Agent → presign (ingest-gw) → PUT segment в MinIO → confirm (ingest-gw) → Kafka (segments.confirmed) → segment-writer (PostgreSQL) + search-service (OpenSearch)

**Playback flow:**
Browser → search-service (поиск) → playback-service (M3U8) → MinIO (proxy stream)

**Control flow:**
CRM/АТС webhook → control-plane → Kafka → Agent
Admin → control-plane → Kafka command → Agent

**S2S авторизация:**
playback-service, search-service → auth-service `/api/v1/internal/check-access` (ABAC)

## Паттерны

- **Мультитенантность:** `tenant_id` во всех таблицах, JWT, S3-путях. Cross-tenant доступ невозможен.
- **JWT:** access + refresh. Claims: user_id, tenant_id, roles[], permissions[], scopes[].
- **Политики записи:** draft → published → archived. Назначение на user/device/org_unit/channel.
- **Идемпотентность:** correlation_id, UUID ключи на все операции.
- **Партиционирование:** таблицы `segment` и `audit_log` — месячные RANGE-партиции по `created_ts`.
- **Аудит:** immutable (triggers блокируют UPDATE/DELETE на audit_log).
- **Производительность:** система должна обеспечивать работу 10 000 одновременно подключенных компьютеров, которые передают на сервер свои записи.

## Kafka топики

| Топик | Producer | Consumer | Назначение |
|-------|----------|----------|------------|
| commands.issued | control-plane | — | Команды агентам |
| device.events | control-plane | — | События устройств |
| segments.confirmed | ingest-gateway | search-service, segment-writer | Подтверждение сегментов |
| segments.written | segment-writer | — | Сегменты записаны в БД |
| webhooks.trigger | control-plane | webhook-worker | Триггеры вебхуков |

## API конвенции

- Пути: `/api/v1/...`
- Формат: JSON, snake_case
- Ошибки: `{"error": "message", "code": "ERROR_CODE"}`
- ID: UUID v4
- Timestamps: ISO 8601 / RFC 3339 в JSON, `timestamptz` в PostgreSQL
- Аутентификация: `Authorization: Bearer <jwt>`, межсервисная: `X-Internal-API-Key`
- Устройства: `X-Device-ID` header

### Backend → Frontend контракт (ВАЖНО!)

- **Jackson глобально настроен на `SNAKE_CASE`** (`application.yml` + `JacksonConfig.java`). Все Java camelCase поля сериализуются/десериализуются как snake_case в JSON.
- **Backend контроллеры возвращают `List<T>` для коллекций** — это сериализуется как JSON-массив `[{...}, {...}]`, а **НЕ** как объект-обёртка `{ items: [...] }`.
- **При написании фронтенда**: API-функции в `web-dashboard/src/api/catalogs.ts` должны указывать тип ответа как массив (`T[]`), а **не** как обёрточный интерфейс (`{ items: T[], total: number }`), если бэкенд возвращает `List<T>`.
- **Типичная ошибка**: фронтенд ожидает `resp.groups` или `resp.items`, а получает массив → `undefined` → crash. Всегда проверять, что тип в axios `get<T>` соответствует фактическому ответу контроллера.
- **Paginated endpoints** (возвращающие `PageResponse<T>`) — корректно возвращают `{ content: [...], page, size, total_elements, total_pages }`. Для них обёрточный тип на фронте правильный.

---

## Трекер задач и тест-кейсов
Когда пользователь просит провести аналитику по задаче - это означает что нужно подготовить подробную пошаговвый документ и положить его в /docs/todo/TASK-<порядковый-номер-задачи>-<название задачи>
Когда пользователь просит декомпозировать задачу - создавай директорию /docs/todo/TASK-<порядковый-номер-задачи>

### Листы

| Лист | Назначение | ID-формат |
|------|-----------|-----------|
| Tasks | Задачи и баги | T-001, T-002, … |
| TestCases | Тест-кейсы | TC-001, TC-002, … |
| Backlog | Бэклог идей и отложенных задач | — |

### Поля Tasks

| Поле | Значения |
|------|---------|
| Тип | `Task`, `Bug` |
| Приоритет | `Low`, `Medium`, `High`, `Critical` |
| Статус | `Open`, `In Progress`, `Done`, `Cancelled` |

### Поля TestCases

| Поле | Значения |
|------|---------|
| Статус | `Pending`, `Pass`, `Fail` |
| Связ. задача | ID из Tasks (например, `T-001`) |

### Правила работы с трекером

- **Перед реализацией любой фичи или фикса** — убедиться, что в Tasks есть соответствующая запись. Если нет — создать.
- **После реализации** — обновить статус задачи на `Done`.
- **Для каждой значимой фичи** — создать тест-кейсы в TestCases и привязать к задаче через поле `Связ. задача`.
- **Деплой на prod** возможен только если все связанные тест-кейсы имеют статус `Pass`.
- Баги (`Bug`) с приоритетом `Critical` блокируют деплой на prod до перехода в `Done`.

### Команды трекера

```bash
# Просмотр
python docs/tracker.py tasks list
python docs/tracker.py tasks list --type Bug
python docs/tracker.py tasks list --status Open
python docs/tracker.py tests list
python docs/tracker.py tests list --status Fail
python docs/tracker.py tests list --feature "Авторизация"

# Создание задачи
python docs/tracker.py tasks add \
  --type Bug \
  --title "Краткое описание" \
  --desc "Подробное описание проблемы" \
  --priority High \
  --assignee "Алексей"

# Создание тест-кейса
python docs/tracker.py tests add \
  --feature "Название модуля" \
  --title "Название тест-кейса" \
  --preconditions "Что должно быть до теста" \
  --steps "1. Шаг один\n2. Шаг два" \
  --expected "Ожидаемый результат" \
  --task T-001

# Обновление задачи
python docs/tracker.py tasks update T-001 --status "In Progress"
python docs/tracker.py tasks update T-001 --status Done
python docs/tracker.py tasks update T-001 --priority Critical --assignee "Алексей"

# Обновление тест-кейса
python docs/tracker.py tests update TC-001 --status Pass --actual "Сработало корректно"
python docs/tracker.py tests update TC-002 --status Fail --actual "Получили 500 вместо ошибки валидации"
```

### Связь с пайплайном деплоя
После проведения тестирования
1. Запустить `python docs/tracker.py tests list --status Fail` — убедиться, что нет упавших тестов.
2. Запустить `python docs/tracker.py tasks list --type Bug --status Open` — убедиться, что нет Critical багов.
3. Если есть блокеры — сообщить пользователю и НЕ деплоить до явного подтверждения.

При выполнении `/deploy` на prod:
1. Запустить `python docs/tracker.py tests list --status Fail` — убедиться, что нет упавших тестов.
2. Запустить `python docs/tracker.py tasks list --type Bug --status Open` — убедиться, что нет Critical багов.
3. Если есть блокеры — сообщить пользователю и НЕ деплоить до явного подтверждения.