# Системная аналитика: Внедрение Apache Kafka

> **Дата:** 2026-03-16
> **Статус:** Планирование
> **Автор:** SA (Claude)
> **Версия:** 3.0
> **Ключевое изменение v3:** Kafka стоит **перед** PostgreSQL — входной буфер, защищающий БД от перегрузки.

---

## 0. Принцип внедрения: Zero Downtime (Strangler Fig)

> **Ни одно изменение не должно ломать текущую работающую систему.**

1. Новые сервисы разворачиваются **параллельно**. Текущие HTTP-маршруты не трогаются.
2. Kafka и consumers работают рядом, пока не верифицированы — ни на что не влияют.
3. **Переключение маршрутов** — в самом конце, одним атомарным шагом.

**Rollback на любом шаге:** feature flag → система возвращается в AS-IS.

---

## 1. Текущее состояние (AS-IS)

### 1.1 Архитектура

```
┌─────────────┐     HTTP      ┌────────────────┐     HTTP      ┌───────────────┐
│ Windows/Mac │ ── heartbeat → │  control-plane │ ── auth ───→ │  auth-service │
│   Agent     │   (poll 30s)  │     :8080      │               │     :8081     │
│             │               └────────────────┘               └───────────────┘
│             │     HTTP      ┌────────────────┐
│             │ ── presign ─→ │ ingest-gateway │ ─sync─→ PostgreSQL
│             │ ── PUT S3 ──→ │     :8084      │ ─sync─→ MinIO (S3 HEAD)
│             │ ── confirm ─→ │                │
└─────────────┘               └────────────────┘
```

### 1.2 Проблема: PostgreSQL — единственная точка приёма нагрузки

```
confirm() = DB SELECT (1-5ms) + S3 HEAD (20-100ms) + DB WRITE ×2 (4-10ms)
          = 25-115ms на каждый вызов, DB-соединение занято всё это время

10K устройств → 167 confirm/сек → HikariCP pool (30) загружен на 40-65%
20K устройств → 334 confirm/сек → pool исчерпан → 503 → потеря данных
```

**Каждый новый consumer (search, webhooks, analytics), добавленный синхронно в confirm(), увеличивает latency и нагрузку на PostgreSQL линейно.**

---

## 2. Целевая архитектура (TO-BE)

### 2.1 Ключевая идея: Kafka ПЕРЕД PostgreSQL

```
БЫЛО (v2.1 — Kafka ПОСЛЕ PostgreSQL):

  Agent → confirm() → [sync DB WRITE] → 200 OK → Kafka (fire-and-forget, после факта)
                       ↑ PostgreSQL — bottleneck, Kafka не помогает

СТАЛО (v3.0 — Kafka ПЕРЕД PostgreSQL):

  Agent → confirm() → [publish в Kafka] → 202 Accepted (1-2ms, мгновенно)
                              ↓
                    segment-writer consumer (batch)
                              ↓
                    [batch INSERT PostgreSQL] (пакетами, в своём темпе)
```

**PostgreSQL больше не принимает удар напрямую от 10K+ агентов.** Kafka — входной буфер, который:
- Принимает сообщения за ~1ms (vs 25-115ms sync DB write)
- Хранит их на диске (retention 7 дней) — ничего не теряется
- Отдаёт consumer'у, который пишет в PostgreSQL **пакетами** в контролируемом темпе

### 2.2 Итоговая схема

```
  ┌─────────────────────────────────────────────────────────────────────────┐
  │                        nginx (load balancer)                            │
  │                  rate limit: 10 req/s per device_id                     │
  └──────────┬──────────────────┬──────────────────┬────────────────────────┘
             │                  │                  │
    ┌────────▼───┐     ┌───────▼────┐     ┌───────▼────┐
    │ ingest-gw  │     │ ingest-gw  │     │ ingest-gw  │
    │ replica 1  │     │ replica 2  │     │ replica N  │
    └─────┬──────┘     └─────┬──────┘     └─────┬──────┘
          │                  │                  │
          │  presign: sync DB (создание segment record, presigned URL)
          │  confirm: Kafka publish ТОЛЬКО (async, 1-2ms) → 202 Accepted
          │
          └──────────────────┴──────────────────┘
                             │
                             ▼
                ┌────────────────────────┐
                │     Apache Kafka       │
                │        :9092           │
                │                        │
                │  ┌──────────────────┐  │
                │  │ segments.ingest  │  │  topic: входящие подтверждения
                │  │  (partitions: 6) │  │  retention: 7 дней
                │  │  key: device_id  │  │
                │  └──────────────────┘  │
                │  ┌──────────────────┐  │
                │  │ commands.issued  │  │  topic: команды агентам
                │  └──────────────────┘  │
                │  ┌──────────────────┐  │
                │  │ device.events    │  │  topic: heartbeat events
                │  └──────────────────┘  │
                │  ┌──────────────────┐  │
                │  │ audit.events     │  │  topic: аудит (365d)
                │  └──────────────────┘  │
                │  ┌──────────────────┐  │
                │  │ webhooks.outbound│  │  topic: webhook delivery
                │  └──────────────────┘  │
                └───┬──────┬──────┬──────┘
                    │      │      │
         ┌──────────┘      │      └──────────┐
         ▼                 ▼                  ▼
┌─────────────────┐ ┌──────────────┐ ┌─────────────────┐
│ segment-writer  │ │search-service│ │ webhook-worker  │
│ consumer group  │ │consumer group│ │ consumer group  │
│                 │ │              │ │                 │
│ Читает из Kafka │ │ Читает из    │ │ Читает из       │
│ BATCH INSERT    │ │ Kafka        │ │ Kafka           │
│ в PostgreSQL    │ │ → OpenSearch  │ │ → HTTP POST     │
│                 │ │              │ │   CRM/АТС       │
│ 50-100 записей  │ │ Idempotent   │ │                 │
│ за раз          │ │ upsert       │ │ Retry + backoff │
└────────┬────────┘ └──────────────┘ └─────────────────┘
         │
         ▼
┌──────────────┐         ┌────────────────────────────┐
│  PostgreSQL  │         │     playback-service        │
│              │ ◄───────│ (читает из PostgreSQL+MinIO) │
│ segments     │         │ НЕ зависит от Kafka         │
│ sessions     │         └────────────────────────────┘
│ devices      │
└──────────────┘
```

### 2.3 Как это защищает PostgreSQL

```
СЦЕНАРИЙ: Пик 20K агентов (334 confirm/сек)

БЕЗ KAFKA (AS-IS):
  334 confirm/сек × 25-115ms = 8-38 одновременных DB-соединений
  HikariCP pool = 30 → исчерпан при ~300-1200 confirm/сек
  → 503 → агент retry → каскадная перегрузка → PostgreSQL OOM/deadlock
  → ПОТЕРЯ ДАННЫХ

С KAFKA ПЕРЕД PostgreSQL (TO-BE):
  334 confirm/сек → Kafka publish (1-2ms) → 202 Accepted агенту
  Kafka topic: 334 msg/сек входит, retention 7 дней

  segment-writer consumer:
    читает batch по 100 сообщений → 1 batch INSERT (5-10ms)
    = 3.3 batch/сек = ~3% нагрузки на PostgreSQL
    → PostgreSQL расслаблен, запас ×30

  Если пик вырастет до 100K confirm/сек:
    Kafka: принимает без проблем (>1M msg/sec)
    segment-writer: consumer lag растёт, пишет в своём темпе
    PostgreSQL: нагрузка не меняется (consumer = throttle)
    → пик спадёт → consumer догонит → lag = 0

СЦЕНАРИЙ: PostgreSQL упал на 30 минут

БЕЗ KAFKA:
  Все confirm() → 500 → потеря данных
  Агент retry → каскадная перегрузка

С KAFKA:
  confirm() → Kafka (работает) → 202 Accepted (агент доволен)
  segment-writer: consumer lag растёт (30 мин × 334/сек = 600K сообщений)
  PostgreSQL поднялся → consumer дочитывает 600K → batch INSERT → ~100 секунд
  → НОЛЬ потерянных данных

СЦЕНАРИЙ: search-service или webhook-worker упал

  segment-writer: продолжает писать в PostgreSQL — НЕ затронут
  search-service: перезапустился → дочитал из Kafka с offset → догнал
  → Каждый consumer изолирован, падение одного не влияет на других
```

### 2.4 Что меняется в confirm()

```java
// AS-IS: синхронная запись в PostgreSQL
@Transactional
public ConfirmResponse confirm(ConfirmRequest req) {
    Segment segment = segmentRepo.findById(req.segmentId());     // DB SELECT
    s3Service.objectExists(segment.getS3Key());                   // S3 HEAD (20-100ms!)
    segment.setStatus("confirmed");
    segmentRepo.save(segment);                                    // DB WRITE
    session.setSegmentCount(session.getSegmentCount() + 1);
    sessionRepo.save(session);                                    // DB WRITE
    return new ConfirmResponse(/*...*/);                          // 200 OK (25-115ms)
}

// TO-BE: publish в Kafka, ответ агенту мгновенно
public ConfirmResponse confirm(ConfirmRequest req) {
    // Минимальная валидация (без DB, без S3)
    kafkaTemplate.send("segments.ingest", req.deviceId(), req);   // Kafka publish (1-2ms)
    return new ConfirmResponse(/*...*/);                          // 202 Accepted (1-2ms)
}

// segment-writer consumer (отдельный сервис или модуль в ingest-gw)
@KafkaListener(topics = "segments.ingest", groupId = "segment-writer")
public void onBatch(List<ConfirmRequest> batch) {                 // batch по 100
    // Валидация: S3 HEAD (можно параллельно на batch)
    // Batch INSERT segments + batch UPDATE sessions
    segmentRepo.saveAll(confirmedSegments);                       // 1 round-trip
    sessionRepo.updateStats(sessionUpdates);                      // 1 round-trip
    // ~5-10ms на batch из 100 записей
}
```

### 2.5 Presign остаётся синхронным

Presign **не переводится на Kafka**, потому что агенту нужен presigned URL **сразу**, чтобы загрузить сегмент в S3. Это лёгкая операция (1 DB INSERT + S3 presign, ~5ms).

```
Agent flow:
  1. presign() → sync → DB INSERT (segment record) + S3 presigned URL → 200 OK (5ms)
  2. PUT segment → MinIO (direct upload, не через наши сервисы)
  3. confirm() → Kafka publish → 202 Accepted (1-2ms)  ← НОВОЕ
     ↓ (async)
  segment-writer → batch DB UPDATE → PostgreSQL (в своём темпе)
```

### 2.6 Eventual Consistency: что видит пользователь

| Момент | Агент видит | Dashboard видит | Задержка |
|--------|------------|-----------------|----------|
| После confirm() | 202 Accepted, переходит к следующему сегменту | Сегмент ещё не в PostgreSQL | 0 |
| Через 1-5 секунд | — | Сегмент появился в PostgreSQL (segment-writer обработал) | 1-5 сек |
| Через 2-10 секунд | — | Сегмент проиндексирован в OpenSearch (search-service) | 2-10 сек |

**Это приемлемо**, потому что:
- Агенту не нужно знать, что сегмент записан в БД — ему достаточно подтверждения приёма
- Dashboard обновляется с задержкой ~5 сек — оператор не заметит
- Поиск работает с задержкой ~10 сек — тоже приемлемо

**Гарантия доставки:** Kafka `acks=all` + `min.insync.replicas=1` (single-broker) → сообщение на диске → segment-writer дочитает.

---

## 3. Kafka Topics

| Topic | Partitions | Retention | Key | Consumer | Назначение |
|-------|-----------|-----------|-----|----------|------------|
| `segments.ingest` | 6 | 7 дней | `device_id` | segment-writer, search-indexer | Входящие confirm-запросы |
| `commands.issued` | 6 | 3 дня | `device_id` | (future: agent push) | Команды агентам |
| `device.events` | 3 | 7 дней | `device_id` | (future: analytics) | online/offline/registered |
| `audit.events` | 3 | 365 дней | `tenant_id` | (future: archiver) | Аудит-события |
| `webhooks.outbound` | 3 | 3 дня | `tenant_id` | webhook-dispatcher | Webhook delivery |

### Consumer Groups

| Consumer Group | Topic | Назначение | Критичность |
|---------------|-------|------------|-------------|
| `segment-writer` | `segments.ingest` | **Batch INSERT в PostgreSQL** | **Critical** — без него данные не попадают в БД |
| `search-indexer` | `segments.ingest` | Индексация в OpenSearch | Medium — search недоступен, но запись работает |
| `webhook-dispatcher` | `webhooks.outbound` | HTTP POST → CRM/АТС | Low — webhooks не влияют на core flow |

### Event Schema: segments.ingest

```json
{
  "event_id": "uuid",
  "timestamp": "2026-03-16T12:00:00Z",
  "tenant_id": "uuid",
  "device_id": "uuid",
  "session_id": "uuid",
  "segment_id": "uuid",
  "sequence_num": 42,
  "s3_key": "tenant_id/device_id/session_id/42.mp4",
  "size_bytes": 1048576,
  "duration_ms": 60000,
  "checksum_sha256": "abc123...",
  "metadata": {
    "resolution": "1920x1080",
    "fps": 1,
    "codec": "h264"
  }
}
```

---

## 4. Оценка производительности

### AS-IS vs TO-BE

| Метрика | AS-IS | TO-BE | Улучшение |
|---------|-------|-------|-----------|
| **Confirm latency (ответ агенту)** | 25-115ms | **1-2ms** | **50-100x** |
| **Confirm throughput** (1 replica) | 260-1200/с | **50 000+/с** (Kafka publish) | **40-200x** |
| **PostgreSQL write load** | 167 INSERT/сек (real-time) | **3.3 batch INSERT/сек** (пакеты по 100) | **PostgreSQL нагрузка ÷50** |
| **PostgreSQL при пике 20K** | Pool exhaustion → 503 | Consumer lag растёт, DB load не меняется | **Полная защита** |
| **PostgreSQL crash 30 мин** | Потеря данных | 0 потерь (Kafka buffer) | **100% durability** |
| Fan-out (новый consumer) | +N ms в confirm() | +0 ms (новый consumer group) | Константный |
| Задержка данных в Dashboard | 0 (sync) | 1-5 сек (eventual) | Компромисс |

### Ресурсные требования

| Компонент | CPU | RAM | Disk | Примечания |
|-----------|-----|-----|------|------------|
| Kafka (KRaft, single-broker) | 1 vCPU | 1-2 GB | 20-100 GB | Основной буфер |
| segment-writer | 0.5 vCPU | 512 MB | — | Может быть модулем в ingest-gw или отдельным сервисом |
| OpenSearch | 2 vCPU | 2-4 GB | 50-200 GB | Полнотекстовый поиск |
| search-service | 0.5 vCPU | 512 MB | — | Kafka consumer + REST API |
| playback-service | 0.5 vCPU | 512 MB | — | PostgreSQL + MinIO proxy |
| PgBouncer (опционально) | 0.1 vCPU | 128 MB | — | Connection pooling |

---

## 5. Фазы внедрения (Strangler Fig)

> Каждая фаза оставляет систему полностью работоспособной.
> Переключение — в самом конце.

### Фаза 1: Kafka — Инфраструктура
> Kafka запускается как изолированный pod. **Влияние на систему: НОЛЬ.**

### Фаза 2: Dual-Write (переходный период)
> ingest-gw пишет и в PostgreSQL (как сейчас), и в Kafka. Feature flag.
> **Влияние: НОЛЬ** — если Kafka down, sync path работает. Агент не замечает разницы.

### Фаза 3: segment-writer consumer + верификация
> Consumer читает из Kafka, пишет batch в PostgreSQL. Параллельно с sync-путём.
> **Влияние: НОЛЬ** — данные дублируются (sync + consumer), идемпотентность через segment_id.

### Фаза 4: Переключение confirm() на Kafka-only
> confirm() перестаёт писать в PostgreSQL напрямую. Только Kafka publish → 202 Accepted.
> **Влияние: confirm latency 25-115ms → 1-2ms. Eventual consistency 1-5 сек.**
> **Rollback: feature flag → sync confirm() обратно.**

### Фаза 5: search-service + OpenSearch
> Новый consumer читает из `segments.ingest`, индексирует в OpenSearch. Тестируется через port-forward.
> **Влияние: НОЛЬ.**

### Фаза 6: playback-service (HLS)
> Новый сервис для воспроизведения. Читает из PostgreSQL + MinIO. Тестируется через port-forward.
> **Влияние: НОЛЬ.**

### Фаза 7: Webhook Worker
> Consumer из Kafka → HTTP POST. **Влияние: НОЛЬ.**

### Фаза 8: Web-dashboard — UI-компоненты
> React-компоненты для поиска, плеера, webhooks. Новые страницы.
> **Влияние: НОЛЬ** (старые страницы не трогаются).

### Фаза 9 (LAST): Переключение маршрутов
> Nginx routes → search-service, playback-service.
> **ЕДИНСТВЕННЫЙ шаг, меняющий UX для пользователей.**

---

## 6. План задач (TODO)

### Фаза 1: Kafka — Инфраструктура

| ID | Задача | Приоритет | Описание |
|----|--------|-----------|----------|
| KFK-001 | K8s манифесты Kafka | Critical | `deploy/k8s/kafka/`: deployment (Kafka 3.7+ KRaft mode, single-broker), service (ClusterIP :9092), PVC (20GB), configmap (server.properties). |
| KFK-002 | Деплой Kafka в k3s (test) | Critical | Docker pull → k3s import → apply manifests в `test-screen-record`. Pod Running. |
| KFK-003 | Создание topics | Critical | Init Job: 5 topics (`segments.ingest`, `commands.issued`, `device.events`, `audit.events`, `webhooks.outbound`) с partitions и retention. |
| KFK-004 | Network Policy | High | Egress/ingress: ingest-gw, control-plane, segment-writer, search-service → kafka:9092. |
| KFK-005 | ConfigMap: KAFKA_BOOTSTRAP_SERVERS | High | `kafka:9092` в configmaps сервисов. Не активирует ничего. |
| KFK-006 | Smoke-тест | High | kafka-console-producer/consumer. Проверить publish/subscribe, topics, consumer groups. |
| KFK-007 | Мониторинг (опционально) | Low | JMX exporter → Prometheus → Grafana. |

### Фаза 2: Dual-Write (переходный период)

> **Цель:** ingest-gw пишет и в PostgreSQL (sync, как сейчас), и в Kafka (async, дополнительно). Это переходный этап для верификации Kafka-пути.

| ID | Задача | Приоритет | Описание |
|----|--------|-----------|----------|
| KFK-010 | Spring Kafka зависимость | Critical | `spring-kafka` в pom.xml ingest-gw и control-plane. `KafkaProducerConfig.java`: producer factory, JSON serializer, `acks=all`, `retries=3`. |
| KFK-011 | Feature flags | Critical | `application.yml`: `kafka.dual-write.enabled: ${KAFKA_DUAL_WRITE:false}` и `kafka.confirm-mode: ${KAFKA_CONFIRM_MODE:sync}` (значения: `sync`, `kafka-only`). По умолчанию всё **ВЫКЛЮЧЕНО**. |
| KFK-012 | EventPublisher компонент | Critical | `@ConditionalOnProperty("kafka.dual-write.enabled")`. Async fire-and-forget. При ошибке — WARN, **никогда не бросает exception в вызывающий код**. |
| KFK-013 | Ingest-gw: dual-write в confirm() | Critical | **Существующий sync DB path НЕ трогается.** После DB commit → `eventPublisher.publish("segments.ingest", ...)`. Если flag=false — вызов не происходит. |
| KFK-014 | Control-plane: publish commands + device events | High | В createCommand() и processHeartbeat() → publish в Kafka. Fire-and-forget. Heartbeat polling продолжает работать. |
| KFK-015 | Включение dual-write на test | High | ConfigMap `KAFKA_DUAL_WRITE=true`, передеплой. `kafka-console-consumer` → проверить что события приходят. |
| KFK-016 | Интеграционные тесты | High | Testcontainers + Kafka: 1) событие в topic; 2) flag=false → ничего; 3) **Kafka down → confirm() проходит успешно** (graceful degradation). |

### Фаза 3: segment-writer consumer + верификация

> **Цель:** Consumer пишет в PostgreSQL batch'ами. На этом этапе данные приходят в БД двумя путями: sync (ingest-gw) и async (segment-writer). Идемпотентность через `ON CONFLICT (segment_id) DO UPDATE`.

| ID | Задача | Приоритет | Описание |
|----|--------|-----------|----------|
| KFK-020 | segment-writer consumer | Critical | Отдельный Spring Boot сервис или `@KafkaListener` модуль в ingest-gw. `groupId="segment-writer"`. Batch consumer: `max.poll.records=100`, manual ack. |
| KFK-021 | Batch DB write logic | Critical | `segmentRepo.saveAll(batch)` — batch INSERT/UPDATE. Idempotent: `ON CONFLICT (id, created_ts) DO UPDATE SET status='confirmed', ...`. Если segment уже confirmed (sync path) — NOP. |
| KFK-022 | S3 validation (async) | High | В consumer (не в confirm()): S3 HEAD check по batch. При расхождении → WARN + метрика. Не блокирует запись в БД. |
| KFK-023 | Session stats aggregation | High | Batch UPDATE `recording_sessions` (segment_count, total_bytes, total_duration). Один UPDATE на session, не на segment. |
| KFK-024 | Consumer lag мониторинг | High | Prometheus метрика `kafka_consumer_lag{group="segment-writer"}`. Алерт при lag > 1000 (30 сек задержки при 100 batch). |
| KFK-025 | Верификация: сравнение путей | Critical | Скрипт: сравнить записи, пришедшие через sync path и через consumer. Все записи должны совпадать. Запустить на test в течение 24ч. |
| KFK-026 | PgBouncer (опционально) | Medium | Если >2 replicas ingest-gw: PgBouncer в transaction mode. Connection multiplexing: 150 клиентских → 30 реальных к PostgreSQL. |

### Фаза 4: Переключение confirm() на Kafka-only

> **Цель:** confirm() перестаёт писать в PostgreSQL напрямую. Только Kafka publish. segment-writer — единственный writer в PostgreSQL для segments.
> **Это ключевой шаг — PostgreSQL полностью защищён от наплыва.**

| ID | Задача | Приоритет | Описание |
|----|--------|-----------|----------|
| KFK-030 | Рефакторинг confirm() | Critical | Если `kafka.confirm-mode=kafka-only`: confirm() → Kafka publish → 202 Accepted. Без DB write, без S3 HEAD. `presign()` остаётся sync (агенту нужен URL сразу). |
| KFK-031 | Nginx rate limiting | High | `limit_req_zone $http_x_device_id zone=ingest:10m rate=10r/s burst=20 nodelay;` на location `/api/ingest/`. Защита от DDoS/misbehaving agents. |
| KFK-032 | Horizontal scaling ingest-gw | Medium | k8s replicas: 1 → 2-3. Теперь confirm() не трогает DB → scaling не упирается в DB pool. |
| KFK-033 | Agent response code update | Medium | Агент: обработать 202 Accepted как успех (если сейчас проверяет только 200). Проверить Windows + macOS агенты. |
| KFK-034 | Включение kafka-only на test | Critical | ConfigMap `KAFKA_CONFIRM_MODE=kafka-only`. Передеплой ingest-gw. Мониторинг: confirm latency, consumer lag, PostgreSQL write rate. |
| KFK-035 | Fallback verification | Critical | Тест: переключить обратно `KAFKA_CONFIRM_MODE=sync` → confirm() работает как раньше. Rollback за 30 секунд. |
| KFK-036 | Load testing | High | k6: 10K simulated agents. Confirm latency p95 < 5ms. Consumer lag < 100. PostgreSQL CPU/IO stable. |

### Фаза 5: search-service + OpenSearch

> **Новый сервис. Не влияет на существующие маршруты.**

| ID | Задача | Приоритет | Описание |
|----|--------|-----------|----------|
| KFK-040 | Деплой OpenSearch в k3s | Critical | `opensearch:2.12.0`. Deployment + Service (:9200) + PVC (50GB). `DISABLE_SECURITY_PLUGIN=true`. |
| KFK-041 | Инициализация search-service | Critical | Spring Boot 3.3, Java 21, порт 8083. Зависимости: `spring-kafka`, `opensearch-java`, `spring-web`. |
| KFK-042 | Kafka Consumer: segments.ingest | Critical | `@KafkaListener(topics="segments.ingest", groupId="search-indexer")`. Upsert в OpenSearch по `segment_id` (idempotent). Manual ack, DLT. |
| KFK-043 | OpenSearch Index Template | Critical | `segments-YYYY-MM` (monthly rotation). Mappings: tenant_id, device_id, session_id, timestamp, s3_key, duration_ms, size_bytes, metadata. |
| KFK-044 | REST API поиска | Critical | `GET /api/v1/search/segments?q=&tenant_id=&device_id=&from=&to=&page=&size=`. Fulltext + filters + date range. |
| KFK-045 | Авторизация | High | JWT + auth-service ABAC. Tenant isolation через query filter. |
| KFK-046 | K8s манифесты + Dockerfile | High | `deploy/k8s/search-service/` + Dockerfile. Multi-stage build. |
| KFK-047 | Backfill скрипт | Medium | `SELECT * FROM segments WHERE status='confirmed'` → bulk index в OpenSearch. Одноразовый для существующих данных. |
| KFK-048 | Верификация на test | High | `kubectl port-forward svc/search-service 8083:8083` → curl. Consumer lag = 0. **Nginx route НЕ добавляется.** |

### Фаза 6: playback-service (HLS)

> **Новый сервис. Не зависит от Kafka — читает из PostgreSQL + MinIO.**

| ID | Задача | Приоритет | Описание |
|----|--------|-----------|----------|
| KFK-050 | Инициализация playback-service | Critical | Spring Boot 3.3, Java 21, порт 8082. `spring-web`, `aws-sdk-s3`, `spring-data-jpa`. |
| KFK-051 | M3U8 Playlist Generator | Critical | `GET /api/v1/playback/sessions/{sessionId}/playlist.m3u8`. PostgreSQL query → HLS playlist. |
| KFK-052 | Segment Proxy / Redirect | Critical | `GET /api/v1/playback/segments/{segmentId}`. MinIO presigned GET → 302 Redirect. |
| KFK-053 | Авторизация | High | JWT + ABAC. Tenant isolation. |
| KFK-054 | K8s манифесты + Dockerfile | High | `deploy/k8s/playback-service/` + Dockerfile. |
| KFK-055 | Верификация на test | High | Port-forward → VLC/ffplay. **Nginx route НЕ добавляется.** |

### Фаза 7: Webhook Worker

| ID | Задача | Приоритет | Описание |
|----|--------|-----------|----------|
| KFK-060 | Flyway миграция: webhook_subscriptions | High | id, tenant_id, url, event_types (jsonb), secret, active, created_ts, updated_ts. |
| KFK-061 | Control-plane: CRUD API webhooks | High | `POST/GET/PUT/DELETE /api/v1/webhooks`. Permissions: `WEBHOOKS:READ/MANAGE`. |
| KFK-062 | Publish webhooks.outbound | High | При событиях → publish если есть подписки. |
| KFK-063 | Webhook dispatcher consumer | High | `@KafkaListener`. HTTP POST + HMAC-SHA256. Retry 3x, exponential backoff. |
| KFK-064 | Webhook delivery log | Medium | Таблица deliveries: status, response_code, attempts. |
| KFK-065 | Верификация на test | High | Создать подписку → confirm → проверить POST на тестовый endpoint. |

### Фаза 8: Web-dashboard — UI

| ID | Задача | Приоритет | Описание |
|----|--------|-----------|----------|
| KFK-070 | React: SearchPage | High | Поиск записей: строка + фильтры + результаты + пагинация. |
| KFK-071 | React: VideoPlayer (HLS.js) | High | session_id → M3U8 → воспроизведение. Play/pause, seek, скорость. |
| KFK-072 | React: SessionDetailPage | High | Метаданные + VideoPlayer + timeline. |
| KFK-073 | React: WebhookManagement | Medium | CRUD подписок, история доставок. |
| KFK-074 | React Router: новые маршруты | High | `/search`, `/recordings/:sessionId`, `/settings/webhooks`. |
| KFK-075 | API-клиенты | High | `api/search.ts`, `api/playback.ts`, `api/webhooks.ts`. |

### Фаза 9 (LAST): Переключение маршрутов

> **Выполняется ТОЛЬКО после полной верификации.**

| ID | Задача | Приоритет | Описание |
|----|--------|-----------|----------|
| KFK-080 | Pre-flight checklist | Critical | Все пункты секции 8 = PASS. |
| KFK-081 | Nginx: route `/api/search/` | Critical | `location /api/search/ { proxy_pass http://search-service:8083/api/; }` |
| KFK-082 | Nginx: route `/api/playback/` | Critical | `location /api/playback/ { proxy_pass http://playback-service:8082/api/; }` |
| KFK-083 | Web-dashboard: API base URL → relative | Critical | `/api/search/`, `/api/playback/`. |
| KFK-084 | Пересборка + деплой web-dashboard | Critical | Docker build → k3s import → rollout restart. |
| KFK-085 | Smoke-тест после cutover | Critical | Поиск, плеер, webhooks — всё работает. **Старые функции не сломаны.** |
| KFK-086 | Мониторинг 24ч | High | Consumer lag, search latency, error rate. Rollback при аномалиях. |

### Сквозные задачи

| ID | Задача | Приоритет | Описание |
|----|--------|-----------|----------|
| KFK-090 | Health checks | High | Kafka connectivity в `/actuator/health`. **Не влияет на readiness** (graceful degradation). |
| KFK-091 | Graceful shutdown | High | Flush producer, drain consumers (timeout 5s). |
| KFK-092 | Dead Letter Topic | Medium | Consumer errors → `*.DLT`. Алерт на DLT > 0. |
| KFK-093 | Метрики Kafka в Prometheus | Medium | Producer/consumer rates, lag, batch size → Grafana. |
| KFK-094 | Runbook: Kafka operations | Medium | Topic management, consumer reset, broker restart. |
| KFK-095 | Документация API (Swagger) | Medium | OpenAPI для search-service, playback-service. |

---

## 7. Зависимости между задачами

```
Фаза 1 (Kafka инфра) ─── zero зависимостей
  KFK-001 → KFK-002 → KFK-003 → KFK-006
  KFK-004 + KFK-005 (параллельно)

Фаза 2 (Dual-Write) ─── зависит от Фазы 1
  KFK-010 → KFK-011 → KFK-012 → KFK-013 + KFK-014 (параллельно)
  KFK-015 (после KFK-013) → KFK-016

Фаза 3 (segment-writer) ─── зависит от Фазы 2 (события в Kafka)
  KFK-020 → KFK-021 → KFK-022 + KFK-023 (параллельно)
  KFK-024 (параллельно)
  KFK-025 (после 24ч работы consumer на test)
  KFK-026 (параллельно, опционально)

Фаза 4 (Kafka-only confirm) ─── зависит от Фазы 3 (consumer верифицирован)
  KFK-030 → KFK-031 + KFK-032 + KFK-033 (параллельно)
  KFK-034 (после KFK-030) → KFK-035 → KFK-036

Фаза 5 (Search) ─── зависит от Фаз 2-3 (events в Kafka)
  KFK-040 (можно параллельно с Фазой 3)
  KFK-041 → KFK-042 → KFK-044 → KFK-045 → KFK-048
  KFK-043 (параллельно с KFK-041)
  KFK-046 (параллельно)
  KFK-047 (после KFK-043 + KFK-042)

Фаза 6 (Playback) ─── НЕ зависит от Kafka
  KFK-050 → KFK-051 → KFK-052 → KFK-053 → KFK-055
  KFK-054 (параллельно)
  *** Можно делать ПАРАЛЛЕЛЬНО с Фазами 3-5 ***

Фаза 7 (Webhooks) ─── зависит от Фазы 2
  KFK-060 → KFK-061 → KFK-062 → KFK-063 → KFK-065
  KFK-064 (после KFK-063)

Фаза 8 (UI) ─── зависит от Фаз 5 + 6 (API готовы)
  KFK-070 + KFK-071 (параллельно)
  KFK-072 (после KFK-071)
  KFK-073 (после KFK-061)
  KFK-074 + KFK-075 (после KFK-070 + KFK-071)

Фаза 9 (LAST) ─── зависит от ВСЕХ
  KFK-080 → KFK-081 + KFK-082 + KFK-083 → KFK-084 → KFK-085 → KFK-086
```

**Параллелизация:**

```
  Фаза 1 → Фаза 2 → Фаза 3 → Фаза 4 (ключевой шаг: Kafka-only confirm)
                 │         │
                 │         ├──→ Фаза 5 (Search) ──┐
                 │         │                       ├──→ Фаза 8 (UI) ──→ Фаза 9 (LAST)
                 │         └──→ Фаза 6 (Playback)─┘
                 └──→ Фаза 7 (Webhooks) ──────────┘
```

---

## 8. Pre-flight Checklist (перед Фазой 9)

### Инфраструктура
- [ ] Kafka pod Running, topics созданы, retention корректный
- [ ] segment-writer consumer: lag = 0, batch writes стабильны
- [ ] OpenSearch pod Running, index template применён
- [ ] search-service pod Running, consumer lag = 0
- [ ] playback-service pod Running, health OK
- [ ] PgBouncer (если используется) — connection stats нормальные

### Kafka-only confirm (Фаза 4 уже активна)
- [ ] confirm() возвращает 202 Accepted за < 5ms (p95)
- [ ] segment-writer пишет в PostgreSQL batch'ами, lag < 100
- [ ] Данные появляются в PostgreSQL через 1-5 сек после confirm()
- [ ] Данные появляются в OpenSearch через 2-10 сек
- [ ] Fallback `KAFKA_CONFIRM_MODE=sync` работает (rollback за 30 сек)

### Функциональность (через port-forward)
- [ ] `GET /api/v1/search/segments?tenant_id=...` → результаты
- [ ] Tenant A НЕ видит данные tenant B
- [ ] `GET /api/v1/playback/sessions/{id}/playlist.m3u8` → валидный HLS
- [ ] HLS воспроизводится в VLC/ffplay
- [ ] Backfill существующих сегментов завершён
- [ ] Webhook delivery работает

### Graceful Degradation
- [ ] Kafka down → confirm() fallback на sync DB write (автоматический)
- [ ] segment-writer down → данные в Kafka, consumer догонит при рестарте
- [ ] OpenSearch down → search-service 503, запись + playback работают
- [ ] search-service down → остальные страницы dashboard работают

### Регрессия
- [ ] Агент: heartbeat работает
- [ ] Агент: presign/confirm цикл успешен (202 Accepted)
- [ ] Dashboard: устройства и записи отображаются (с задержкой ~5 сек)
- [ ] Авторизация: login/logout/token refresh
- [ ] Device tokens CRUD
- [ ] Recording toggle

---

## 9. Риски и митигации

| Риск | Влияние | Митигация |
|------|---------|-----------|
| **Kafka down (kafka-only mode)** | **Critical**: confirm принят, но не записан в PostgreSQL | **Автоматический fallback** на sync DB write. Feature flag + health check Kafka → если Kafka unhealthy, confirm() переключается на sync. |
| **segment-writer consumer lag** | Medium: данные в Dashboard с задержкой | Мониторинг lag. Увеличить concurrency/partitions. Batch size tuning. |
| **Eventual consistency: пользователь не видит свежий сегмент** | Low: задержка 1-5 сек | Приемлемо для use case. Dashboard может показывать "обновление..." |
| **Дублирование при dual-write** | Low | `ON CONFLICT DO UPDATE` — idempotent upsert. |
| **PostgreSQL crash при kafka-only** | **НОЛЬ** | Kafka хранит сообщения (7 дней). Consumer дочитает после recovery. |
| **Потеря сообщений в Kafka** | Low | `acks=all`, `min.insync.replicas=1`, retention 7d. Backfill скрипт как safety net. |
| **OpenSearch OOM** | Medium | JVM heap 2GB, monthly rotation, k8s resource limits. |
| **Ресурсы сервера** | Medium | Проверить CPU/RAM shepaland-cloud. Kafka + OpenSearch = ~3 vCPU, 4-6 GB RAM доп. |

---

## 10. Сравнение архитектур

| | v2.1 (Kafka ПОСЛЕ PostgreSQL) | v3.0 (Kafka ПЕРЕД PostgreSQL) |
|---|---|---|
| **Защита PostgreSQL от перегрузки** | НЕТ — sync write в confirm() | **ДА** — Kafka буфер, batch write |
| Confirm latency | 25-115ms (sync DB + S3) | **1-2ms** (Kafka publish) |
| PostgreSQL write load (10K agents) | 167 INSERT/сек (real-time) | **3.3 batch/сек** (÷50) |
| PostgreSQL при пике 20K | Pool exhaustion → 503 | **Consumer lag, DB stable** |
| PostgreSQL crash | Потеря данных | **0 потерь (Kafka buffer 7d)** |
| Consistency model | Strong (sync) | Eventual (1-5 сек) |
| Сложность | Ниже | Выше (consumer, idempotency, fallback) |
| Rollback | Feature flag | Feature flag + dual-write period |

---

## 11. Выбор Kafka vs NATS

| Критерий | Kafka | NATS JetStream |
|----------|-------|----------------|
| Spring интеграция | `spring-kafka` — first-class, `@KafkaListener`, batch consumer | `jnats` — ручная конфигурация |
| Batch consumption | Нативный (`max.poll.records`) | Нет нативного batch |
| Log-based retention | Да — consumer может перечитать с любого offset | Ограничено |
| Consumer Groups | Зрелые, rebalancing, offset management | Work queues |
| Throughput | >1M msg/sec | >10M msg/sec (но без batch consumer) |
| Exactly-once | Да (idempotent producer + transactions) | Нет |
| **Итог для "Kafka перед PostgreSQL"** | **Выбран**: batch consumer критичен для batch DB writes | Нет нативного batch → batch писать вручную |
