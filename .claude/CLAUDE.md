# Screen Recorder

Мультитенантная платформа записи экранов операторов контактного центра.
H.264 fMP4 запись → сегментация → S3 хранение → HLS воспроизведение → полнотекстовый поиск.

## Стек

- **Backend:** Java 21, Spring Boot 3.x, Spring Security (JWT), Spring Data JPA, Spring WebFlux (ingest), Flyway (миграции)
- **Frontend:** React 18, Vite, TypeScript, Tailwind CSS, Axios, React Router v6
- **macOS Agent:** Swift 5.10, ScreenCaptureKit, AVAssetWriter, SQLite
- **Windows Agent:** Java, SQLite
- **Инфраструктура:** PostgreSQL 16 (на хосте, не в k8s), MinIO (S3), NATS JetStream, OpenSearch 2.12, k3s, Traefik
- **Мониторинг:** Prometheus, Grafana, Loki, Tempo, AlertManager

## Сервисы и порты

| Сервис | Порт | Назначение |
|--------|------|------------|
| auth-service | 8081 | JWT, RBAC (6 ролей, 27 пермишенов), ABAC, аудит |
| control-plane | 8080 | Устройства, политики записи, команды агентам (NATS), вебхуки |
| ingest-gateway | 8084 | Приём видеосегментов: presign → upload → confirm → MinIO |
| playback-service | 8082 | HLS M3U8 плейлисты, проксирование сегментов из MinIO |
| search-service | 8083 | NATS consumer → OpenSearch индексация, полнотекстовый поиск |
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

## Развёртывание

Два сервера, два стейджинга:

| Среда | Сервер | Namespace | URL | БД |
|-------|--------|-----------|-----|----|
| test | shepaland-cloud | `test-screen-record` | `https://services-test.shepaland.ru/screenrecorder` | `prg_test` |
| prod | shepaland-videocalls-test-srv | `prod-screen-record` | `https://services.shepaland.ru/screenrecorder` | `prg_prod` |

- **shepaland-cloud** (158.160.222.120): k3s, cert-manager + ClusterIssuer, PostgreSQL на хосте
- **shepaland-videocalls-test-srv** (158.160.130.90): k3s, Traefik ACME, PostgreSQL на хосте (172.17.0.1:5432)

### Path-based routing

Оба стейджинга используют path `/screenrecorder`. Traefik StripPrefix middleware убирает `/screenrecorder` перед пересылкой в nginx. Vite `base: "/screenrecorder/"`, React Router `basename="/screenrecorder"`.

### Пайплайн деплоя

1. **test** — сборка + деплой на shepaland-cloud. Smoke-тесты, функциональное/интеграционное тестирование.
2. **prod** — деплой на shepaland-videocalls-test-srv ТОЛЬКО после: все тест-кейсы passed + явное подтверждение от пользователя. Никогда не деплоить на prod без спроса.

## Архитектура (ключевое)

Data Plane (видеопоток) отделён от Control Plane (команды, конфигурация). Все сервисы stateless.

**Ingest flow:**
Agent → presign (ingest-gw) → PUT segment в MinIO → confirm (ingest-gw) → PostgreSQL + NATS event → search-service → OpenSearch

**Playback flow:**
Browser → search-service (поиск) → playback-service (M3U8) → MinIO (proxy stream)

**Control flow:**
CRM/АТС webhook → control-plane → NATS → Agent
Admin → control-plane → NATS command → Agent

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

## NATS JetStream потоки

| Поток | Subjects | Retention |
|-------|----------|-----------|
| COMMANDS | commands.> | 72h |
| EVENTS | events.>, segments.> | 168h |
| WEBHOOKS | webhooks.> | 72h |
| AUDIT | audit.> | 1 год |

## API конвенции

- Пути: `/api/v1/...`
- Формат: JSON, snake_case
- Ошибки: `{"error": "message", "code": "ERROR_CODE"}`
- ID: UUID v4
- Timestamps: ISO 8601 / RFC 3339 в JSON, `timestamptz` в PostgreSQL
- Аутентификация: `Authorization: Bearer <jwt>`, межсервисная: `X-Internal-API-Key`
- Устройства: `X-Device-ID` header
