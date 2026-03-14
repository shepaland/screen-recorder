# Pre-Implementation Analysis: Сервис распознавания и классификации действий пользователя по записи экрана

**Дата:** 2026-03-14
**Автор:** Architect + System Analyst
**Статус:** Draft
**Проект:** Кадеро (Screen Recorder Platform)

---

## 1. Executive Summary

### Можно ли внедрять?

**Да, но с существенными оговорками.** Текущая платформа имеет зрелый ingestion pipeline (presign → upload → confirm), S3-хранилище видеосегментов и event-driven архитектуру через NATS JetStream. Это создаёт точку интеграции для нового сервиса. Однако в проекте **полностью отсутствует ML/CV-контур** — нет GPU-инфраструктуры, нет MLOps-процессов, нет опыта с media processing на серверной стороне.

### Главный архитектурный риск

**Несоразмерность вычислительных требований.** Текущая инфраструктура — два k3s-сервера (Yandex Cloud VM) с лимитами 512Mi–768Mi RAM на сервис, без GPU. ML/CV pipeline для OCR + UI detection + classification потребует на порядок больше ресурсов. Попытка запустить его в текущем контуре либо деградирует основную систему, либо не даст приемлемого качества.

### Главный организационно-технический блокер

**Отсутствие ML/MLOps компетенции и инфраструктуры.** Команда работает в стеке Java/Spring + React + Swift/C#. Нет опыта с Python ML-стеком, нет pipeline для обучения/валидации моделей, нет GPU-серверов, нет процесса labeling и review.

### Рекомендуемый формат внедрения

**Гибридный (Option C):** отдельный ML processing контур (Python, GPU-сервер или облачные GPU), интегрированный с текущей системой через NATS events и общий S3 (MinIO). Результаты анализа хранятся в PostgreSQL текущей инфраструктуры, UI встраивается в web-dashboard.

### MVP recommendation

**Offline batch OCR + keyword search.** Извлечение текста из ключевых кадров (1 кадр каждые N секунд) с помощью Tesseract/PaddleOCR. Результат — индексируемый текст, привязанный к segment_id + timestamp. Поиск через OpenSearch (который уже запланирован). Без ML-классификации, без UI detection, без real-time.

### Production recommendation

**Модульный offline pipeline** с orchestration через NATS + очередь задач. Frame extraction → OCR → UI detection → event reconstruction → classification. Каждый шаг — отдельный worker. Human-in-the-loop review через dedicated UI. Модели версионируются, результаты поддерживают reprocessing.

---

## 2. AS-IS Assessment

### 2.1 Основные подсистемы

| Подсистема | Компоненты | Зрелость |
|-----------|-----------|----------|
| **Authentication & RBAC** | auth-service (JWT, 6 ролей, 27 пермишенов, ABAC, OAuth, OTP) | Высокая |
| **Device Management** | control-plane (heartbeat, commands, policies, NATS) | Высокая |
| **Video Ingestion** | ingest-gateway (presign → S3 PUT → confirm), agents (Windows/macOS) | Высокая |
| **Object Storage** | MinIO (S3-compatible), bucket `prg-segments` | Средняя (single replica) |
| **User Activity Tracking** | ingest-gateway (focus intervals, audit events, sessions) | Средняя |
| **Playback** | playback-service (HLS M3U8) | **Не развёрнут** |
| **Search** | search-service (NATS → OpenSearch) | **Не развёрнут** |
| **Web UI** | web-dashboard (React 18, 33 страницы) | Высокая |
| **Мониторинг** | Prometheus, Grafana, Loki, Tempo | **Не развёрнут** |
| **CI/CD** | Отсутствует (ручная сборка на shepaland-cloud) | **Отсутствует** |

### 2.2 Ключевые сервисы и их ответственность

**auth-service (8081):** Единая точка аутентификации и авторизации. JWT access/refresh, RBAC с 27 пермишенами, ABAC через `/api/v1/internal/check-access`. Межсервисная авторизация через `X-Internal-API-Key`. Audit log с immutable записями (triggers блокируют UPDATE/DELETE). Flyway миграции V1–V35.

**control-plane (8080):** Управление устройствами. Heartbeat (30s interval, 90s timeout). NATS JetStream для команд агентам (`commands.{device_id}`). Device status log, command distribution. Flyway не использует (полагается на auth-service миграции).

**ingest-gateway (8084):** Ядро data plane. 3-step idempotent upload (presign → PUT → confirm). Запись в partitioned таблицы `segments` и `app_focus_intervals`. User activity tracking (V29): focus intervals с browser detection и domain extraction. Dashboard analytics. Flyway миграции V29–V33 (собственная БД-схема). HikariCP pool: 30 connections (больше остальных сервисов).

**web-dashboard (80):** React SPA за nginx reverse proxy. Path-based routing через Traefik (`/screenrecorder`). Nginx маршрутизирует `/api/cp/` → control-plane, `/api/ingest/` → ingest-gateway, `/api/` → auth-service, `/prg-segments/` → MinIO. Security headers (CSP, HSTS, X-Frame-Options: DENY).

### 2.3 Текущие интеграции

```
Agent ──presign──→ ingest-gateway ──presigned URL──→ Agent ──PUT──→ MinIO
Agent ──confirm──→ ingest-gateway ──NATS event──→ [search-service] ──→ [OpenSearch]
Agent ──heartbeat──→ control-plane ──response──→ Agent (commands, settings)
control-plane ──NATS──→ Agent (commands: start/stop recording, update policy)
auth-service ←──check-access──→ other services (S2S ABAC)
CRM/ATS ──webhook──→ control-plane ──NATS──→ Agent
```

### 2.4 Хранение данных

| Хранилище | Тип | Что хранит | Особенности |
|-----------|-----|-----------|-------------|
| PostgreSQL (на хосте) | RDBMS | Users, devices, sessions, segments metadata, audit, focus intervals | Partitioned tables (monthly), на хосте 172.17.0.1:5432, NOT в k8s |
| MinIO | Object Storage (S3) | Video segments (fMP4, H.264) | Bucket `prg-segments`, path: `{tenant_id}/{device_id}/{session_id}/{seq}.mp4`, single replica |
| OpenSearch 2.12 | Search engine | Full-text search по сегментам | **Не развёрнут** |
| SQLite (на агентах) | Embedded DB | Pending segments queue, credentials | Только на стороне агента |

### 2.5 Есть ли подходящее место для media processing?

**Нет.** На серверной стороне нет никакого media processing. Агенты делают encoding (H.264/fMP4) локально через ScreenCaptureKit (macOS) и FFmpeg CLI (Windows). Сервер только принимает готовые сегменты и хранит. Playback-service (HLS) запланирован, но не развёрнут. FFmpeg на серверной стороне не установлен. GPU на серверах нет.

### 2.6 Event-driven / workflow-driven механика

**Event-driven: частично.** NATS JetStream сконфигурирован (streams COMMANDS, EVENTS, WEBHOOKS, AUDIT), используется для device commands и segment events. Однако полноценного event-sourcing нет — основной flow синхронный (REST API). NATS используется для fire-and-forget уведомлений, не для orchestration.

**Workflow-driven: отсутствует.** Нет workflow engine (Temporal, Airflow, Prefect). Нет DAG-based processing. Нет step-by-step orchestration с retry/compensation.

### 2.7 ML/CV workload контур

**Полностью отсутствует.** Нет:
- GPU-серверов или GPU-instances
- Python runtime на серверной стороне
- Model registry
- Feature store
- Training pipeline
- Inference runtime (TorchServe, Triton, etc.)
- MLOps tooling (MLflow, W&B, etc.)

### 2.8 UI для review/операторской работы

**Частично есть.** Web-dashboard имеет развитый UI с таблицами, фильтрацией, пагинацией, timeline-компонентами, видеоплеером. Есть страницы аналитики (UserReportsPage, DashboardPage). Однако нет:
- Labeling UI
- Review workflow (approve/reject/relabel)
- Annotation overlay на видео
- Side-by-side comparison (video + extracted events)

### 2.9 Access control, audit, logging, monitoring

| Аспект | Состояние | Детали |
|--------|----------|--------|
| **RBAC** | Зрелый | 6 ролей, 27 пермишенов, role cloning, custom roles |
| **ABAC** | Работает | S2S check-access, scope-based (tenant/global) |
| **Multi-tenancy** | Строгий | tenant_id во всех таблицах, JWT claims, S3 paths |
| **Audit** | Immutable | Partitioned audit_log, triggers блокируют UPDATE/DELETE |
| **Logging** | Базовый | Spring Boot logging (stdout), no centralized aggregation |
| **Monitoring** | **Отсутствует** | Prometheus/Grafana/Loki/Tempo запланированы, не развёрнуты |
| **Alerting** | **Отсутствует** | AlertManager не настроен |

---

## 3. Readiness Assessment

| Направление | Готовность | Аргументация | Что есть | Чего не хватает |
|-------------|-----------|-------------|---------|----------------|
| **Архитектурная** | **Medium** | Microservice architecture, NATS event bus, S3 storage — хорошая база. Но нет workflow orchestration, нет media processing pipeline. | Сервисная архитектура, REST API, NATS JetStream, S3 presigned URLs, partitioned tables | Workflow engine, media processing layer, ML service mesh, async job queue |
| **Инфраструктурная** | **Low** | Два k3s-сервера без GPU, лимиты 512Mi–768Mi. Нет горизонтального масштабирования для heavy workloads. Manual build pipeline. | k3s cluster, Traefik ingress, NetworkPolicies, Docker multi-stage builds | GPU nodes, job scheduler (k8s Jobs/CronJobs), auto-scaling, CI/CD pipeline, artifact registry |
| **Data readiness** | **Medium-High** | Видеосегменты уже в MinIO с чёткой структурой (`tenant/device/session/seq.mp4`). Metadata в PostgreSQL (segments, sessions, focus_intervals). Есть correlation_id. | Structured video storage, segment metadata, user activity data (focus intervals), session model, tenant isolation | Derived artifacts storage scheme, frame extraction pipeline, OCR results schema, event model для extracted actions |
| **Security/Privacy** | **Medium** | Строгая multi-tenancy, JWT + RBAC/ABAC. Но видеозаписи экрана содержат PII (пароли, переписки, персональные данные). Нет data masking/redaction. | tenant_id isolation, encrypted tokens, immutable audit, network policies, CSP headers | PII detection/redaction в OCR output, access control для analysis results, consent management, data retention для derived artifacts |
| **Operational** | **Low** | Ручной deploy (scp + docker build + k3s ctr import). Нет CI/CD. Нет centralized logging. Нет monitoring dashboards. Добавление ML pipeline значительно усложнит operations. | Manual deploy pipeline (работает), rollout restart, health probes | CI/CD, centralized logging (Loki), monitoring (Grafana), alerting, runbooks, on-call procedures |
| **ML/MLOps** | **None** | Полностью отсутствует. Нет Python runtime, нет model registry, нет GPU, нет inference runtime, нет training pipeline. | Ничего | Всё: GPU infra, Python env, model registry, inference runtime, training pipeline, experiment tracking, A/B testing framework |
| **Observability** | **Low** | Только health probes (/actuator/health). Нет метрик, нет трейсинга, нет централизованных логов. Для ML pipeline observability критична (quality metrics, latency, throughput). | Spring Boot Actuator health, k8s readiness/liveness probes | Prometheus metrics, Grafana dashboards, Loki log aggregation, Tempo tracing, custom ML quality metrics |

---

## 4. Gap Analysis

### 4.1 Ingestion

| Аспект | Текущее состояние | Требуется | Gap | Критичность | Закрытие |
|--------|------------------|-----------|-----|-------------|---------|
| Video segments в S3 | fMP4 сегменты по 60 секунд, H.264, 1 FPS | Доступ к raw video для frame extraction | **Minimal gap** — видео уже в MinIO | Low | Новый сервис читает из MinIO по s3_key из segments table |
| Trigger для начала анализа | NATS event `segments.confirmed` (запланирован, частично используется) | Event-driven trigger при появлении нового сегмента | **Small gap** — NATS event есть, но consumer не реализован | Medium | Создать NATS consumer в analysis-service по `segments.>` |
| Batch reprocessing | Нет механизма | Возможность переобработать все сегменты сессии/устройства/периода | **Significant gap** — нет job queue | High | Job queue (BullMQ/Celery) + API endpoint для trigger reprocessing |

### 4.2 Object Storage

| Аспект | Текущее состояние | Требуется | Gap | Критичность | Закрытие |
|--------|------------------|-----------|-----|-------------|---------|
| Raw video | MinIO, bucket `prg-segments`, single replica | Надёжное хранение raw + derived artifacts | **Medium gap** — single replica MinIO, нет backup | High | MinIO replication или backup policy; отдельный bucket для derived artifacts |
| Derived artifacts | Нет | Хранение кадров, OCR JSON, event timelines, model outputs | **Full gap** | High | Новый bucket `prg-analysis-artifacts`, path: `{tenant_id}/{session_id}/{pipeline_version}/{artifact_type}/` |
| Storage growth | ~50-200KB per segment (60s@1FPS) → ~500MB/device/month | + extracted frames + OCR JSON + events → x3-x10 от текущего объёма | **Significant gap** — storage capacity planning не сделан | High | Capacity planning, lifecycle policies (TTL для frames, сжатие, archival tier) |

### 4.3 Media Processing

| Аспект | Текущее состояние | Требуется | Gap | Критичность | Закрытие |
|--------|------------------|-----------|-----|-------------|---------|
| Frame extraction | Нет серверного media processing | FFmpeg для извлечения кадров из fMP4 | **Full gap** | Critical | FFmpeg в Docker-контейнере analysis-worker |
| Video preprocessing | Нет | Deinterlacing, denoising, resolution normalization | **Full gap** | Medium | FFmpeg filters в preprocessing step |
| Keyframe detection | Нет | Определение значимых изменений экрана (scene change detection) | **Full gap** | High | FFmpeg scene detection filter или OpenCV frame diff |

### 4.4 Perception: OCR / UI Detection

| Аспект | Текущее состояние | Требуется | Gap | Критичность | Закрытие |
|--------|------------------|-----------|-----|-------------|---------|
| OCR | Нет | Извлечение текста из кадров экрана | **Full gap** | Critical | Tesseract 5 (CPU) или PaddleOCR (GPU) в Docker worker |
| UI element detection | Нет | Обнаружение кнопок, полей ввода, меню, иконок | **Full gap** | High | YOLO/DETR fine-tuned on UI elements, или rule-based (контрастные контуры) |
| Layout analysis | Нет | Понимание структуры UI (header, sidebar, main content, modal) | **Full gap** | Medium | Может быть отложено на Phase 2-3 |

### 4.5 Event Model

| Аспект | Текущее состояние | Требуется | Gap | Критичность | Закрытие |
|--------|------------------|-----------|-----|-------------|---------|
| Agent-level events | device_audit_events (LOGON/LOGOFF, PROCESS_START/STOP, SESSION_LOCK/UNLOCK) | Canonical event model: click, type, scroll, navigate, switch_app, etc. | **Significant gap** — текущие events на уровне OS, а не UI actions | Critical | Новая таблица `analysis_events` с типами UI-действий + привязка к timestamp/frame |
| Focus intervals | app_focus_intervals (process_name, window_title, browser, domain, duration) | Гранулярность до отдельных действий внутри фокуса | **Medium gap** — focus intervals = макро-уровень, actions = микро-уровень | High | analysis_events дополняют focus_intervals, не заменяют |
| Event timeline | Нет unified timeline | Единая временная шкала: agent events + extracted actions + OCR timestamps | **Full gap** | High | Timeline API, объединяющий device_audit_events + app_focus_intervals + analysis_events |

### 4.6 Action Classification

| Аспект | Текущее состояние | Требуется | Gap | Критичность | Закрытие |
|--------|------------------|-----------|-----|-------------|---------|
| UI action classification | Нет | click, type, scroll, drag, select, navigate, etc. | **Full gap** | High | ML model или rule-based classifier на основе OCR + UI detection |
| Business action classification | app_groups (V31) — ручная группировка приложений | Автоматическая классификация: "оформление заказа", "консультация", "поиск информации" | **Full gap** | High (long-term) | Зависит от quality of event extraction; Phase 3 |
| Scenario detection | Нет | Последовательность действий → бизнес-сценарий | **Full gap** | Medium (long-term) | Sequence classification, Phase 3 |

### 4.7 Labeling / Review

| Аспект | Текущее состояние | Требуется | Gap | Критичность | Закрытие |
|--------|------------------|-----------|-----|-------------|---------|
| Labeling UI | Нет | Интерфейс для просмотра кадров, разметки действий, подтверждения OCR | **Full gap** | Critical (для ML quality) | Dedicated page в web-dashboard или отдельный labeling tool (Label Studio) |
| Review workflow | Нет | approve/reject/relabel цикл для результатов анализа | **Full gap** | High | Status machine (pending → reviewed → approved/rejected) + UI |
| Ground truth dataset | Нет | Размеченные данные для обучения и валидации моделей | **Full gap** | Critical (для ML) | Labeling process → export → training dataset |

### 4.8 Orchestration

| Аспект | Текущее состояние | Требуется | Gap | Критичность | Закрытие |
|--------|------------------|-----------|-----|-------------|---------|
| Workflow engine | Нет | Multi-step pipeline: extract → preprocess → OCR → detect → classify → store | **Full gap** | Critical | Temporal (recommended) или custom NATS-based orchestration |
| Job queue | Нет | Очередь задач с приоритетами, retry, dead letter | **Full gap** | Critical | Redis + BullMQ (JS) или Celery (Python) или NATS JetStream work queues |
| Idempotent reprocessing | Presign/confirm idempotent, но для analysis нет | Повторная обработка сегмента с новой версией модели | **Significant gap** | High | pipeline_version + idempotent storage schema |

### 4.9 Analytics / Search

| Аспект | Текущее состояние | Требуется | Gap | Критичность | Закрытие |
|--------|------------------|-----------|-----|-------------|---------|
| Full-text search | OpenSearch запланирован, не развёрнут | Поиск по OCR-текстам, действиям, сценариям | **Medium gap** — планы есть, реализации нет | High | Deploy OpenSearch + index analysis results (OCR text, events, classifications) |
| Analytics queries | Dashboard analytics в ingest-gateway (PostgreSQL) | Агрегации по extracted actions (top actions, common scenarios, productivity metrics) | **Medium gap** | Medium | Расширить dashboard endpoints или отдельный analytics API |

### 4.10 Security / Privacy

| Аспект | Текущее состояние | Требуется | Gap | Критичность | Закрытие |
|--------|------------------|-----------|-----|-------------|---------|
| PII в видео | Видео хранится as-is, нет обработки PII | OCR будет извлекать пароли, номера карт, переписки | **Critical gap** | Critical | PII detection + redaction в OCR output; access control на analysis results |
| Tenant isolation для ML | tenant_id во всех таблицах | ML models не должны утекать cross-tenant | **Medium gap** | Critical | Tenant-scoped inference; results scoped by tenant_id; model training на isolated datasets |
| Consent | Нет модели consent | Регуляторные требования к анализу поведения сотрудников | **Gap зависит от юрисдикции** | High | Юридическая консультация, consent management module |

### 4.11 Scalability

| Аспект | Текущее состояние | Требуется | Gap | Критичность | Закрытие |
|--------|------------------|-----------|-----|-------------|---------|
| Compute | 2 k3s VM, 512Mi-768Mi per service, no GPU | GPU for OCR/detection, CPU for preprocessing | **Critical gap** | Critical | Отдельный GPU-сервер или cloud GPU instances |
| Horizontal scaling | 1-2 replicas, manual deploy | Auto-scaling workers по backlog | **Significant gap** | High | HPA или external autoscaler, worker pool |
| Storage | Single-replica MinIO | Replicated storage, lifecycle policies | **Medium gap** | High | MinIO erasure coding или replicated mode; lifecycle/tiering |

### 4.12 CI/CD & Release Strategy

| Аспект | Текущее состояние | Требуется | Gap | Критичность | Закрытие |
|--------|------------------|-----------|-----|-------------|---------|
| CI/CD | Нет (ручная сборка на сервере) | Automated build/test/deploy для ML pipeline | **Full gap** | High | GitHub Actions или аналог; Docker registry; helm charts |
| Model deployment | N/A | Версионирование и деплой ML-моделей | **Full gap** | High | Model registry (MLflow или S3-based), canary rollout |

### 4.13 Model Lifecycle / Versioning

| Аспект | Текущее состояние | Требуется | Gap | Критичность | Закрытие |
|--------|------------------|-----------|-----|-------------|---------|
| Model registry | Нет | Хранение, версионирование, rollback ML-моделей | **Full gap** | High | MLflow Model Registry или S3 + metadata DB |
| A/B testing | Нет | Сравнение версий модели на одних данных | **Full gap** | Medium | Pipeline version в results schema → compare |
| Reprocessing | Нет | Переобработка с новой моделью | **Full gap** | High | Idempotent pipeline + job API |

### 4.14 Support & Operations

| Аспект | Текущее состояние | Требуется | Gap | Критичность | Закрытие |
|--------|------------------|-----------|-----|-------------|---------|
| Monitoring | Нет | Метрики ML quality (OCR accuracy, classification F1), pipeline throughput, error rates | **Full gap** | High | Prometheus + custom metrics + Grafana dashboards |
| Alerting | Нет | Alert при деградации quality, pipeline stall, storage overflow | **Full gap** | High | AlertManager rules |
| Runbooks | Нет | Процедуры для типовых инцидентов | **Full gap** | Medium | Документация (Phase 2) |

---

## 5. Constraints & Architectural Drivers

### 5.1 Ограничения

#### Технические
- **Стек:** Java 21 / Spring Boot — основной backend стек. ML/CV обычно Python-based. Два runtime = два мира.
- **Агенты:** записывают 1 FPS, 720p, H.264 fMP4. Качество кадров ограничено (сжатие, артефакты).
- **Формат сегментов:** fMP4 с 60-секундными чанками. Frame extraction потребует FFmpeg.
- **S3 key structure:** `{tenant}/{device}/{session}/{seq}.mp4` — фиксированная, менять нельзя.
- **Partitioned tables:** monthly partitions в PostgreSQL — новые таблицы должны следовать той же схеме.

#### Организационные
- **Команда:** Java/React/Swift/C# компетенции. Нет ML-инженера.
- **Процесс:** ручной deploy pipeline, нет CI/CD. Добавление ML-сервиса усложнит.
- **Скорость:** один разработчик + Claude Code. Ограниченная пропускная способность.

#### Инфраструктурные
- **Серверы:** два Yandex Cloud VM (k3s). Нет GPU.
- **PostgreSQL:** на хосте, не в k8s. Single instance (не HA).
- **MinIO:** single replica, нет erasure coding. Потеря данных = потеря видео.
- **Network:** NATS в k8s, PostgreSQL на хосте (172.17.0.1). Analysis service должен иметь доступ к обоим.

#### Регуляторные / безопасностные
- **PII:** записи экрана содержат конфиденциальные данные (пароли, переписки, персональные данные клиентов).
- **Российское законодательство:** ФЗ-152 (персональные данные), ТК РФ (мониторинг сотрудников). Необходимо согласие работников.
- **Compliance:** doc `docs/legal-saas-russia.md` существует — нужно проверить покрытие.
- **Data residency:** данные должны храниться на территории РФ (Yandex Cloud — ОК).

#### Бюджетные / ресурсные
- **GPU:** отсутствует. Облачные GPU (Yandex Cloud GPU) — значительные затраты.
- **Storage:** рост x3-x10 при хранении extracted frames и OCR results.
- **Compute:** OCR + detection — CPU-intensive (Tesseract) или GPU-required (PaddleOCR, YOLO).
- **Допущение:** бюджет ограничен, предпочтительны open-source решения и self-hosted.

#### По latency
- **Offline-first обязателен.** Real-time analysis при 10,000 устройств и 1 FPS — это 10,000 кадров/секунду. Невозможно без кластера GPU.
- **Приемлемая задержка:** от минут до часов между записью и доступностью результатов анализа.

#### По storage
- **Текущий объём:** ~500MB/device/month (при 1FPS, 720p, 8 часов/день).
- **10,000 устройств:** ~5TB/month raw video.
- **+ derived artifacts:** x3-x10 → 15-50TB/month. Требует lifecycle policies.

#### По поддержке и эксплуатации
- **Один оператор.** Нет dedicated DevOps или SRE.
- **Ручной deploy.** Каждый новый сервис = ещё один deployment artifact.
- **Нет alerting.** Проблемы обнаруживаются вручную.

### 5.2 Архитектурные драйверы

| Драйвер | Приоритет | Обоснование |
|---------|----------|-------------|
| **Privacy** | Critical | Видео содержит PII. OCR извлекает текст. Утечка = инцидент. Tenant isolation обязательна. |
| **Cost efficiency** | Critical | Ограниченный бюджет. GPU дорогие. Нужны pragmatic решения (CPU-first OCR, smart frame sampling). |
| **Integration simplicity** | High | Основная система работает. Новый сервис не должен ломать existing flow. Loosely coupled через NATS. |
| **Maintainability** | High | Один оператор. Чем проще — тем лучше. Monolithic analysis worker лучше, чем mesh из 10 микросервисов. |
| **Scalability** | Medium | 10,000 устройств — цель. Но MVP может начать с 10-100. Архитектура должна позволять горизонтальное масштабирование, но не требовать его сразу. |
| **Reproducibility** | Medium | pipeline_version + input hash = deterministic output. Для model evolution и debugging. |
| **Explainability** | Medium | Пользователь должен понимать, откуда взялась классификация. Привязка к кадру + OCR text + detected UI elements. |
| **Human-in-the-loop** | Medium | ML будет ошибаться. Review workflow обязателен для production quality. Также нужен для ground truth creation. |

---

## 6. Architecture Options

### Option A: Автономный модуль рядом с проектом

**Суть:** Отдельный Python-сервис (`analysis-service`) в том же k3s кластере. Общий MinIO и PostgreSQL. NATS для trigger. Никаких изменений в существующих сервисах, кроме добавления NATS consumer.

**Что меняется в текущей системе:**
- Ничего в Java-сервисах
- Новый nginx route `/api/analysis/` → analysis-service
- Новые таблицы в PostgreSQL (или отдельная БД)
- Новые страницы в web-dashboard

**Новые компоненты:**
- `analysis-service` (Python, FastAPI/Flask) — API + NATS consumer + job queue
- `analysis-worker` (Python) — FFmpeg + OCR + detection workers
- Redis (для job queue)

**Зависимости:**
- MinIO (чтение видео)
- PostgreSQL (чтение segments metadata, запись results)
- NATS (получение segment events)
- auth-service (валидация JWT, check-access)

**Плюсы:**
- Нулевой impact на existing services
- Python — natural fit для ML/CV
- Может быть развёрнут/свёрнут независимо
- Отдельный release cycle

**Минусы:**
- Два runtime (Java + Python) = усложнение operations
- Нужен Redis (ещё один компонент)
- GPU в k3s требует специальной настройки
- Отдельная CI/CD pipeline для Python

**Риски:**
- Resource contention в k3s (CPU-intensive OCR + existing services)
- PostgreSQL connection pool exhaustion (ещё один сервис)
- Дополнительная нагрузка на MinIO (read segments + write artifacts)

**Стоимость внедрения:** Средняя. Python service + Redis + k8s manifests + web UI.
**Стоимость сопровождения:** Высокая. Два стека, два deploy pipeline.

**Когда уместен:** MVP и начальные этапы. Быстрый старт без риска для production.
**Когда не стоит выбирать:** Если нет компетенции в Python/ML и никто не будет поддерживать.

---

### Option B: Встроенный pipeline в Java backend

**Суть:** Новый Spring Boot сервис (`analysis-service`) на Java. Использует Apache Tika для OCR, JavaCV (обёртка OpenCV) для frame extraction, правила (rule-based) для event detection. Всё в том же стеке.

**Что меняется в текущей системе:**
- Новый Java-сервис в существующем Maven multi-module
- Стандартный deploy (как auth-service, control-plane)
- NATS consumer + REST API

**Новые компоненты:**
- `analysis-service` (Java 21, Spring Boot) — с Apache Tika, JavaCV, FFmpeg wrapper

**Плюсы:**
- Единый стек (Java). Один deploy pipeline. Одна команда.
- Стандартные Spring Boot паттерны (health probes, Actuator, HikariCP)
- Проще для текущей команды поддерживать
- Нет нового runtime

**Минусы:**
- Java-экосистема для ML/CV значительно беднее Python
- Apache Tika OCR (Tesseract wrapper) — ограниченное качество
- JavaCV — неудобный, менее документированный, чем Python OpenCV
- Нет доступа к state-of-the-art моделям (PaddleOCR, YOLO, Transformers) без ONNX/TensorFlow Java
- Значительный boilerplate для ML pipeline на Java
- Java inference медленнее Python для большинства ML frameworks

**Риски:**
- Lock-in в Java ML ecosystem (ограниченный выбор моделей)
- Качество OCR и detection ниже, чем в Python alternatives
- Невозможность использовать современные pre-trained модели
- Тяжело найти Java ML-инженера

**Стоимость внедрения:** Средняя (инфраструктурно), высокая (по quality ограничений).
**Стоимость сопровождения:** Низкая (единый стек).

**Когда уместен:** Если нужен только базовый OCR (Tesseract) и rule-based classification. Если ML/deep learning не планируется.
**Когда не стоит выбирать:** Если ожидается эволюция моделей, fine-tuning, deep learning detection.

---

### Option C: Гибридный — внешний ML processing контур (РЕКОМЕНДУЕМЫЙ)

**Суть:** Python ML processing на отдельном сервере (GPU-capable). Интеграция с текущей системой через NATS + S3 + PostgreSQL. Java-сервисы не меняются. API для результатов — тонкий Java proxy или прямой доступ из web-dashboard.

**Что меняется в текущей системе:**
- Новые таблицы в PostgreSQL (или отдельная БД analysis)
- Новый nginx route `/api/analysis/` → analysis-api
- Новые страницы в web-dashboard
- NATS: новый consumer group для analysis pipeline

**Архитектура:**

```
[Current k3s cluster]                    [Analysis Server (GPU-capable)]

MinIO ←── S3 read ──────────────────── analysis-worker (Python)
  ↑                                       ↓
  └── S3 write (artifacts) ──────────── analysis-worker

NATS ──── segments.confirmed ─────────→ analysis-orchestrator
                                         ↓
                                       job queue (Redis)
                                         ↓
                                       workers (FFmpeg → OCR → detect → classify)
                                         ↓
PostgreSQL ←── write results ────────── analysis-worker

auth-service ←── validate JWT ────────── analysis-api (FastAPI)
                                         ↑
web-dashboard ── /api/analysis/ ──────→ analysis-api
```

**Новые компоненты (на отдельном сервере):**
- `analysis-api` (Python, FastAPI) — REST API для results, job management
- `analysis-orchestrator` (Python) — NATS consumer, создаёт jobs
- `analysis-worker` (Python) — FFmpeg + OCR + detection + classification
- Redis — job queue (Celery или RQ)
- (Опционально) MLflow — model registry

**Плюсы:**
- Python — best-in-class для ML/CV. Доступ ко всем моделям (Tesseract, PaddleOCR, YOLO, Transformers)
- Изоляция от production workload. ML не деградирует основную систему
- GPU на отдельном сервере (можно арендовать по необходимости)
- Независимый scale. Workers масштабируются горизонтально
- Model evolution без impact на основную систему
- Можно начать с CPU-only (Tesseract) и добавить GPU позже

**Минусы:**
- Два сервера = усложнение networking (S3 endpoint, NATS endpoint, PostgreSQL access)
- Два стека = два deploy pipeline
- Latency на S3 read через network (vs local в k3s)
- Дополнительные затраты на сервер

**Риски:**
- Network connectivity между серверами (firewall, latency)
- PostgreSQL connection от внешнего сервера (security, IP whitelisting)
- Операционная сложность (мониторинг двух систем)

**Стоимость внедрения:** Высокая (отдельный сервер, networking, deploy pipeline).
**Стоимость сопровождения:** Средняя (изолированная система, но отдельный мониторинг).

**Когда уместен:** Когда планируется ML evolution, fine-tuning моделей, GPU inference. Когда основная инфраструктура не должна деградировать.
**Когда не стоит выбирать:** Если бюджет на отдельный сервер недоступен. Если объём данных мал (< 100 устройств).

---

### Сравнительная таблица

| Критерий | Option A (автономный в k3s) | Option B (Java built-in) | Option C (внешний ML) |
|----------|---------------------------|-------------------------|---------------------|
| Impact на production | Minimal | Minimal | None |
| ML ecosystem | Full (Python) | Limited (Java) | Full (Python) |
| GPU support | Сложно в k3s | Нет | Нативно |
| Operational complexity | High (2 stacks) | Low (1 stack) | High (2 servers) |
| Model evolution | Easy | Hard | Easy |
| Cost (infra) | Low (shared k3s) | Low | Medium-High (extra server) |
| OCR quality potential | High | Medium | High |
| Scalability | Limited by k3s | Limited by k3s | Independent |
| **Рекомендация** | MVP Phase 1 | Не рекомендуется | Phase 2+ |

---

## 7. Recommended Target Design

> **Допущение:** выбран гибридный подход (Option C), но MVP стартует как Option A (Python service в k3s, CPU-only OCR). При росте нагрузки — вынос на отдельный сервер.

### 7.1 Ingestion

**Где живёт:** Существующий ingest-gateway остаётся без изменений. NATS event `segments.confirmed` используется как trigger.

**Новый consumer:** `analysis-orchestrator` подписывается на `segments.>` в NATS JetStream. При получении event создаёт analysis job в очереди.

### 7.2 Хранение raw video и derived artifacts

**Raw video:** Остаётся в MinIO, bucket `prg-segments`. Без изменений.

**Derived artifacts:** Новый bucket `prg-analysis` в MinIO.

```
prg-analysis/
  {tenant_id}/
    {session_id}/
      {pipeline_version}/
        frames/
          frame_000001.jpg
          frame_000060.jpg
          ...
        ocr/
          frame_000001.json    # { text_blocks: [...], language, confidence }
          frame_000060.json
        ui_detection/
          frame_000001.json    # { elements: [{type, bbox, confidence, text}] }
        events/
          timeline.json        # Reconstructed event timeline
        classification/
          actions.json         # Classified actions
          scenarios.json       # Detected business scenarios
        metadata.json          # Pipeline run metadata
```

### 7.3 Orchestration

**MVP (Phase 1):** Простая очередь задач.
- NATS consumer получает `segments.confirmed` → создаёт job
- Job queue: Redis + Celery (Python) или NATS JetStream work queue
- Pipeline: sequential steps в одном worker process

```
segments.confirmed event
    ↓
[analysis-orchestrator]
    ↓ create job
[Redis queue]
    ↓ pick job
[analysis-worker]
    ├── 1. Download segment from MinIO
    ├── 2. Extract keyframes (FFmpeg scene detection)
    ├── 3. OCR each keyframe (Tesseract/PaddleOCR)
    ├── 4. (Phase 2) UI detection
    ├── 5. (Phase 2) Event reconstruction
    ├── 6. (Phase 3) Action classification
    ├── 7. Store results → PostgreSQL + MinIO
    └── 8. Publish NATS event: analysis.completed
```

**Production (Phase 2+):** Temporal workflow engine.
- Каждый step — отдельная activity
- Retry, timeout, compensation per step
- Parallel execution where possible
- Visibility into pipeline progress

### 7.4 Preprocessing / OCR / UI Detection / Classification

| Step | Tool | Phase | Resource |
|------|------|-------|----------|
| Frame extraction | FFmpeg (scene detection + fixed interval) | Phase 1 | CPU |
| OCR | Tesseract 5 (CPU) → PaddleOCR (GPU, Phase 2) | Phase 1 | CPU → GPU |
| UI detection | Rule-based contours (Phase 2) → YOLO fine-tuned (Phase 3) | Phase 2 | CPU → GPU |
| Event reconstruction | Heuristic (text diff + app switch detection) | Phase 2 | CPU |
| UI action classification | Rule-based (Phase 2) → ML classifier (Phase 3) | Phase 2-3 | CPU → GPU |
| Business scenario classification | Rule-based templates → sequence classifier (Phase 3) | Phase 3 | CPU → GPU |

### 7.5 Canonical Event Model

Новая таблица `analysis_events` (partitioned monthly):

```sql
CREATE TABLE analysis_events (
    id UUID,
    created_ts TIMESTAMPTZ NOT NULL,           -- partition key
    tenant_id UUID NOT NULL,
    session_id UUID NOT NULL,
    segment_id UUID,
    pipeline_version VARCHAR(50) NOT NULL,     -- "v1.0", "v1.1"

    -- Timing
    event_ts TIMESTAMPTZ NOT NULL,             -- when the action occurred
    frame_index INTEGER,                        -- source frame number

    -- Event type
    event_type VARCHAR(50) NOT NULL,           -- 'ocr_text', 'ui_click', 'app_switch', 'text_input', 'navigation', 'scroll'
    event_subtype VARCHAR(100),                -- specific action subtype

    -- Context
    process_name VARCHAR(512),
    window_title VARCHAR(2048),

    -- OCR data
    ocr_text TEXT,                              -- extracted text at this moment
    ocr_confidence REAL,

    -- UI detection
    ui_element_type VARCHAR(50),               -- 'button', 'input', 'menu', 'link', 'dropdown'
    ui_element_text VARCHAR(500),
    ui_element_bbox JSONB,                     -- {x, y, width, height}

    -- Classification
    action_label VARCHAR(100),                 -- classified action (e.g., 'form_fill', 'navigation', 'search')
    action_confidence REAL,
    business_scenario VARCHAR(200),            -- higher-level scenario

    -- Provenance
    model_version VARCHAR(100),                -- which model produced this

    -- Artifacts
    frame_s3_key VARCHAR(1024),                -- S3 path to source frame

    PRIMARY KEY (id, created_ts)
) PARTITION BY RANGE (created_ts);
```

**Связь с текущей моделью:**

```
recording_sessions (1) ──→ (N) segments ──→ (N) analysis_events
                      ──→ (N) app_focus_intervals
                      ──→ (N) device_audit_events

analysis_events enriches focus_intervals:
  - focus_interval: "Chrome, 15:00-15:05, google.com"
  - analysis_events: "typed search query 'customer order 12345'", "clicked 'Search' button", "scrolled results"
```

### 7.6 Review Workflow

**Таблица `analysis_reviews`:**

```sql
CREATE TABLE analysis_reviews (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    session_id UUID NOT NULL,
    pipeline_version VARCHAR(50) NOT NULL,

    status VARCHAR(20) NOT NULL DEFAULT 'pending',  -- pending, in_review, approved, rejected
    reviewer_user_id UUID,
    review_ts TIMESTAMPTZ,
    review_notes TEXT,

    -- Quality scores
    ocr_quality_score REAL,          -- 0-1, reviewer assessment
    event_quality_score REAL,
    classification_quality_score REAL,

    -- Corrections
    corrections JSONB,               -- [{event_id, field, old_value, new_value}]

    created_ts TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_ts TIMESTAMPTZ
);
```

**Review UI (в web-dashboard):**
- Страница со списком pending reviews (по tenant)
- Детальный view: video player слева + extracted timeline справа
- Возможность подтвердить/отклонить/исправить каждый event
- Export approved data как training dataset

### 7.7 Базы данных и хранилища

| Хранилище | Назначение | Новое? |
|-----------|-----------|--------|
| PostgreSQL (prg_test/prg_prod) | analysis_events, analysis_reviews, analysis_jobs | Новые таблицы в существующей БД |
| MinIO bucket `prg-analysis` | Extracted frames, OCR JSON, detection results | Новый bucket |
| Redis | Job queue для Celery workers | Новый компонент |
| OpenSearch | Index OCR text + analysis events для full-text search | Уже запланирован |

### 7.8 API (новые endpoints)

**analysis-api (FastAPI или Spring Boot proxy):**

```
# Job management
POST   /api/v1/analysis/jobs                    # Trigger analysis for session/segment
GET    /api/v1/analysis/jobs                    # List jobs (paginated, filtered)
GET    /api/v1/analysis/jobs/{id}               # Job status + progress
DELETE /api/v1/analysis/jobs/{id}               # Cancel job

# Results
GET    /api/v1/analysis/sessions/{id}/timeline  # Unified event timeline
GET    /api/v1/analysis/sessions/{id}/events    # Extracted events (paginated)
GET    /api/v1/analysis/sessions/{id}/ocr       # OCR results
GET    /api/v1/analysis/sessions/{id}/summary   # Classification summary

# Review
GET    /api/v1/analysis/reviews                 # Pending reviews
POST   /api/v1/analysis/reviews/{id}/approve    # Approve
POST   /api/v1/analysis/reviews/{id}/reject     # Reject
PUT    /api/v1/analysis/reviews/{id}/corrections # Submit corrections

# Search
GET    /api/v1/analysis/search?q=...&tenant_id=...  # Full-text search in OCR results

# Reprocessing
POST   /api/v1/analysis/reprocess               # Reprocess with new model version
```

**Новые permissions (auth-service):**

```
ANALYSIS:READ          -- просмотр результатов анализа
ANALYSIS:MANAGE        -- trigger analysis, reprocessing
ANALYSIS:REVIEW        -- review workflow (approve/reject)
ANALYSIS:SEARCH        -- full-text search в OCR
ANALYSIS:EXPORT        -- export datasets
```

### 7.9 Versioning артефактов и моделей

**Pipeline version:** семантическое версионирование (`v1.0.0`). Хранится в `analysis_events.pipeline_version` и `prg-analysis/{tenant}/{session}/{pipeline_version}/`.

**Model version:** каждая ML-модель имеет version tag. Хранится в `analysis_events.model_version`.

**Reprocessing:** новая версия pipeline создаёт новый набор результатов. Старые результаты не удаляются (immutable). Пользователь выбирает, какую версию смотреть.

### 7.10 Reprocessing

```
POST /api/v1/analysis/reprocess
{
    "scope": "session",           // session | device | tenant | date_range
    "session_id": "uuid",
    "pipeline_version": "v1.1.0", // new version to use
    "force": false                // skip if already processed with this version
}
```

Pipeline:
1. Query segments для scope
2. Create job per segment
3. Process with specified pipeline version
4. Store results with new pipeline_version
5. Mark old results as superseded (not deleted)

### 7.11 Observability

| Метрика | Тип | Назначение |
|---------|-----|-----------|
| `analysis_jobs_total` | Counter | Общее кол-во jobs |
| `analysis_jobs_in_progress` | Gauge | Текущие in-flight jobs |
| `analysis_job_duration_seconds` | Histogram | Время обработки |
| `analysis_ocr_confidence_avg` | Gauge | Средняя уверенность OCR |
| `analysis_frames_processed_total` | Counter | Обработано кадров |
| `analysis_errors_total` | Counter | Ошибки по типу |
| `analysis_storage_bytes` | Gauge | Объём artifacts в S3 |
| `analysis_queue_depth` | Gauge | Глубина очереди jobs |
| `analysis_pipeline_version` | Info | Текущая версия pipeline |

**Логирование:** structured JSON logs (correlation_id, tenant_id, session_id, pipeline_version).

**Трейсинг:** OpenTelemetry spans для каждого шага pipeline.

### 7.12 Privacy / Security

| Мера | Реализация |
|------|-----------|
| **Tenant isolation** | Все таблицы: tenant_id. S3 paths: `{tenant_id}/...`. API: JWT tenant_id check |
| **PII detection** | Regex-based PII detector в OCR output (email, phone, card numbers, passwords) |
| **PII redaction** | Опциональный флаг `redact_pii: true` в pipeline config. Заменяет PII на `[REDACTED]` |
| **Access control** | Новые permissions: ANALYSIS:READ/MANAGE/REVIEW/SEARCH/EXPORT |
| **Audit** | Все API calls логируются в audit_log (immutable) |
| **Encryption** | S3 artifacts — server-side encryption (MinIO SSE). Results в PostgreSQL — column-level encryption для sensitive OCR text (опционально) |
| **Data retention** | Configurable TTL для analysis artifacts (отдельно от raw video) |

---

## 8. Non-Functional Requirements

| NFR | Почему важен | Что проверить в текущем проекте | Риск при отсутствии |
|-----|-------------|-------------------------------|-------------------|
| **Performance** | OCR + detection = CPU/GPU intensive. При 10K устройств — 10K сегментов/час | Текущая нагрузка на k3s (CPU/memory utilization) | Pipeline не справляется, backlog растёт бесконечно |
| **Throughput** | Должен обрабатывать ≥ incoming rate сегментов | Текущий rate сегментов (segments/min by tenant) | Analysis отстаёт от ingestion, результаты устаревают |
| **Storage growth** | x3-x10 от текущего. 10K devices × 500MB/mo = 5TB/mo raw. + 15-50TB/mo artifacts | MinIO capacity, disk space on servers | Disk full → service outage |
| **Retry / Idempotency** | FFmpeg/OCR могут fail. Network issues. Worker crash | Текущий idempotent presign/confirm pattern | Duplicate results, lost work, inconsistent state |
| **Reliability** | Pipeline должен переживать restart без потери progress | NATS JetStream durability (ack/nack), job queue persistence | Lost jobs, reprocessing всего с начала |
| **RPO/RTO** | Допустимая потеря данных / время восстановления | Backup policy для PostgreSQL, MinIO | Потеря результатов анализа, длительный downtime |
| **Auditability** | Кто запустил analysis, кто reviewed, какая модель использовалась | Текущий audit_log pattern | Нет traceability, compliance violations |
| **Explainability** | Пользователь должен понимать, почему действие классифицировано так | Нет текущих аналогов | Недоверие к системе, невозможность debugging |
| **Model traceability** | Какая версия модели дала конкретный результат | Нет текущих аналогов | Невозможность debugging, невозможность rollback |
| **Data retention** | Derived artifacts могут иметь отличный от raw video TTL | Текущая retention policy (партиции по месяцам) | Неконтролируемый рост storage, compliance issues |
| **Redaction/Masking** | OCR извлекает PII: пароли, номера карт, персональные данные | Текущая PII policy (нет) | Утечка PII, legal liability |
| **Access control** | Не все пользователи должны видеть OCR-текст и events | Текущие 27 permissions + RBAC | Несанкционированный доступ к содержимому экранов |
| **Tenancy isolation** | OCR/analysis results не должны утекать между тенантами | Текущий tenant_id enforcement | Cross-tenant data leak |

---

## 9. Risk Register

| # | Risk | Impact | Likelihood | Why it matters | Mitigation | Residual risk |
|---|------|--------|-----------|----------------|-----------|---------------|
| R1 | **Privacy leakage через OCR** | Critical | High | OCR извлекает пароли, переписки, персональные данные клиентов из видео. Утечка = юридический инцидент + потеря доверия | PII detector + redaction pipeline. Access control на results. Audit logging. Data retention policies | PII detector может пропустить нестандартные форматы |
| R2 | **Poor OCR quality** | High | Medium | Сжатый H.264 (1 FPS, 720p, low quality) → артефакты. Мелкий текст плохо распознаётся. Разные шрифты, языки | Предварительное тестирование на реальных записях. Выбор OCR engine по результатам бенчмарка. Preprocessing (upscale, denoise, sharpen) | При сильном сжатии OCR будет ненадёжным. Может потребоваться повышение FPS/quality на агенте |
| R3 | **UI variability** | High | High | Разные OS, разрешения, темы, приложения. UI detection модель обучена на ограниченном наборе → false positives/negatives на новых UI | Rule-based fallback + fine-tuning на реальных данных. Labeling pipeline. Continuous improvement | Длительный процесс fine-tuning. Новые приложения = новые ошибки |
| R4 | **Отсутствие event layer** | High | Certain | Нет canonical event model для UI actions. Без него — нет classification, нет scenarios | Проектирование event schema ДО реализации pipeline. Итеративная доработка | Schema evolution при росте функционала |
| R5 | **Перегрузка инфраструктуры** | Critical | Medium | k3s с 512Mi-768Mi limits. CPU-intensive OCR на том же кластере → деградация auth/ingest/control-plane | Отдельные node pools или отдельный сервер для analysis workers. Resource limits + quotas | Стоимость дополнительного сервера |
| R6 | **Высокая стоимость хранения** | High | High | x3-x10 рост при хранении frames + OCR + events. 10K devices → десятки TB/month | Lifecycle policies (TTL для frames, compress OCR). Smart frame sampling (не каждый кадр). Archive tier | Storage cost растёт с числом устройств |
| R7 | **Отсутствие review workflow** | Medium | Certain | Без human review качество classification непроверяемо. Нет ground truth для улучшения моделей | Phase 1: manual spot-check. Phase 2: dedicated review UI + workflow | Review bottleneck при росте объёма |
| R8 | **Невозможность объяснить классификацию** | Medium | Medium | "Почему система решила, что это 'оформление заказа'?" — нет ответа → недоверие | Привязка classification к конкретным frames + OCR text. Explainability report per classification | Сложные сценарии трудно объяснить |
| R9 | **Деградация текущей системы** | Critical | Medium | Общий PostgreSQL, MinIO, k3s. Analysis workload перегружает shared resources → auth/ingest тормозит | Separate DB connection pool. Separate MinIO bucket. Rate limiting на analysis jobs. Resource quotas | Shared PostgreSQL — узкое место |
| R10 | **Отсутствие MLOps discipline** | High | Certain | Нет model registry, нет experiment tracking, нет A/B testing. Models deployed ad-hoc → untraceable | MLflow для model registry. Pipeline versioning. Rollback capability | MLOps discipline требует времени и дисциплины |
| R11 | **Real-time вместо offline-first** | High | Low | Попытка real-time analysis при 10K devices = требует GPU cluster → prohibitive cost | Offline-first design (batch processing, minutes-to-hours delay) | Stakeholder expectations management |
| R12 | **Vendor/tool lock-in** | Medium | Low | Привязка к конкретному OCR engine или ML framework → сложно мигрировать | Abstraction layer для OCR/detection. Standard formats (JSON, PNG). Open-source tools first | Abstraction добавляет complexity |

---

## 10. Open Questions

### Product / Business

1. **Какие конкретные бизнес-действия нужно классифицировать?** (Пример: "оформление заказа", "консультация", "работа с CRM"). Без этого невозможно проектировать classifier.
2. **Кто целевой пользователь результатов анализа?** Руководитель контакт-центра? Аналитик? Оператор? Это определяет UI и granularity.
3. **Какова допустимая задержка** между записью и доступностью результатов? Минуты? Часы? Следующий рабочий день?
4. **Нужна ли real-time аналитика** или достаточно batch отчётов (daily/weekly)?
5. **Какие KPI должна поставлять система?** Производительность оператора? Время на операцию? Compliance с регламентом?
6. **Нужен ли функционал для конкретных тенантов** или это platform-wide feature?

### Architecture

7. **Где будет жить analysis server?** В текущем k3s? На отдельном сервере? В облаке (Yandex Cloud GPU)?
8. **Нужен ли отдельный PostgreSQL** для analysis data или использовать существующий? (capacity impact)
9. **Как интегрировать с запланированным OpenSearch?** Analysis results → OpenSearch? Отдельный index?
10. **Какой SLA у analysis pipeline?** (throughput, latency, availability)

### Infra / DevOps

11. **Есть ли бюджет на GPU-сервер?** Yandex Cloud GPU instances: от ~50K руб/мес за NVIDIA T4.
12. **Каков текущий disk space** на shepaland-cloud и shepaland-videocalls-test-srv? Сколько TB доступно?
13. **Планируется ли CI/CD?** ML pipeline без CI/CD = manual hell.
14. **Есть ли backup policy** для PostgreSQL и MinIO?

### Security / Legal

15. **Есть ли юридическое заключение** по обработке содержимого записей экрана (OCR, text extraction)? ФЗ-152, ТК РФ.
16. **Требуется ли дополнительное согласие сотрудников** на OCR/text extraction (beyond recording consent)?
17. **Нужна ли возможность redaction** (удаление/маскирование PII из результатов)?
18. **Должны ли результаты анализа подлежать "праву на забвение"** (right to be forgotten)?

### Data / ML

19. **Есть ли образцы записей** для тестирования OCR quality? Нужны реальные записи для бенчмарка.
20. **Какие языки интерфейса** у операторов? (Русский? Английский? Mixed?) — влияет на OCR model selection.
21. **Какие приложения** используют операторы? (CRM-системы? Браузеры? Excel?) — влияет на UI detection.
22. **Какой минимальный acceptable OCR accuracy?** 80%? 90%? 95%?
23. **Есть ли готовые размеченные данные** или нужно начинать labeling с нуля?

### Operations / Support

24. **Кто будет поддерживать ML pipeline?** Текущая команда? Нужен ML-инженер?
25. **Каков процесс обновления моделей?** Как часто? Кто решает о rollout?
26. **Нужна ли поддержка offline анализа** (без подключения к серверу)?

---

## 11. Implementation Roadmap

### Phase 0 — Discovery / Validation (2-4 недели)

**Цель:** Подтвердить feasibility OCR на реальных записях. Определить quality baseline.

**Scope:**
- Собрать 50-100 реальных записей (разные приложения, разрешения, пользователи)
- Протестировать Tesseract 5 vs PaddleOCR на этих записях
- Измерить OCR quality (accuracy, speed, resource consumption)
- Протестировать FFmpeg frame extraction (scene detection vs fixed interval)
- Определить optimal frame sampling rate
- Юридическая проверка (ФЗ-152, ТК РФ)

**Deliverables:**
- OCR benchmark report (accuracy by app type, font size, resolution)
- Frame extraction benchmark (quality vs speed vs storage)
- Resource estimation (CPU/GPU/RAM per segment)
- Legal assessment
- Go/No-Go decision

**Dependencies:**
- Доступ к реальным записям (MinIO, прод или тест)
- Юрист / compliance officer

**Acceptance criteria:**
- OCR accuracy ≥ 80% на текстовых элементах
- Processing time ≤ 5 минут per 60-sec segment (CPU-only)
- Legal green light

**Риски:**
- OCR quality недостаточна на сжатых записях → нужно повышать quality/FPS на агенте
- Юридические ограничения → блокер для всего проекта

---

### Phase 1 — MVP (4-8 недель)

**Цель:** Базовый OCR pipeline с поиском по тексту.

**Scope:**
- `analysis-worker` (Python): FFmpeg frame extraction → Tesseract OCR → store results
- Таблица `analysis_events` (PostgreSQL, partitioned)
- Bucket `prg-analysis` (MinIO)
- NATS consumer для trigger (или manual API trigger)
- Базовый API: trigger analysis, get results, search OCR text
- Минимальный UI: страница "Анализ записей" в web-dashboard
- Integration с OpenSearch для full-text search по OCR

**Deliverables:**
- analysis-worker Docker image
- PostgreSQL migration для analysis_events
- API endpoints (trigger, results, search)
- Web dashboard page (timeline + OCR text view)
- Deployment manifests (k8s)

**Dependencies:**
- Phase 0 Go/No-Go
- OpenSearch deployment (или PostgreSQL full-text search как fallback)
- Redis deployment (для job queue)

**Acceptance criteria:**
- Сегмент обрабатывается за < 5 минут (CPU)
- OCR text доступен для поиска
- Tenant isolation подтверждена
- Нет деградации existing services

**Риски:**
- Resource contention в k3s
- OCR quality на production данных ниже, чем в benchmark

---

### Phase 2 — Production Hardening (6-12 недель)

**Цель:** Надёжный production pipeline с UI detection и review workflow.

**Scope:**
- GPU support (отдельный сервер или cloud GPU)
- PaddleOCR (лучше quality) вместо/рядом с Tesseract
- Rule-based UI element detection
- Event reconstruction (text diff → typed text, button clicks, navigation)
- Review workflow (approve/reject/correct)
- PII detection + redaction
- Monitoring (Prometheus metrics, Grafana dashboard)
- Reprocessing API
- Pipeline versioning
- Lifecycle policies для artifacts (TTL, archival)

**Deliverables:**
- GPU-enabled analysis-worker
- UI detection module
- Event reconstruction module
- Review UI в web-dashboard
- PII detection/redaction pipeline
- Monitoring dashboard
- Reprocessing API + UI

**Dependencies:**
- GPU infrastructure
- Labeling data (from Phase 1 review)
- Monitoring stack (Prometheus/Grafana)

**Acceptance criteria:**
- OCR accuracy ≥ 90%
- UI detection precision ≥ 70%
- PII redaction catch rate ≥ 95%
- Review workflow functional (approve/reject/correct)
- Pipeline throughput: 1000+ segments/hour
- Monitoring alerts working

**Риски:**
- GPU infrastructure cost
- UI detection quality на diverse applications
- Review bottleneck (human capacity)

---

### Phase 3 — ML Maturity / Scale (ongoing)

**Цель:** ML-based classification, scenario detection, auto-improvement.

**Scope:**
- ML action classifier (trained on labeled data from Phase 2)
- Business scenario detection (sequence classification)
- Active learning loop (model improves from review corrections)
- A/B testing для model versions
- Auto-scaling workers
- Advanced analytics (productivity reports, compliance checks, scenario comparison)
- Model registry (MLflow)
- Temporal workflow engine (replace simple queue)

**Deliverables:**
- Trained action classifier model
- Scenario detection pipeline
- Active learning pipeline
- MLflow integration
- Advanced analytics dashboard
- Auto-scaling infrastructure

**Dependencies:**
- Sufficient labeled data (1000+ reviewed sessions)
- ML engineering expertise
- Temporal deployment

**Acceptance criteria:**
- Action classification F1 ≥ 0.8
- Scenario detection precision ≥ 0.7
- Active learning improves metrics measurably per cycle
- Pipeline scales to 10K devices

**Риски:**
- Insufficient labeled data
- ML model does not generalize across tenants/apps
- Operational complexity of ML stack

---

## 12. Final Recommendation

### Стоит ли внедрять сейчас?

**Да, но начинать с Phase 0 (Discovery/Validation).** Проект имеет зрелую базу для интеграции (S3 video storage, NATS events, multi-tenant architecture, rich API surface). Однако полное отсутствие ML/CV инфраструктуры и компетенции означает, что сразу прыгать в реализацию — рискованно.

### В каком формате?

**Offline-first batch pipeline.** Не real-time. Segment-triggered processing с задержкой от минут до часов. CPU-first (Tesseract), GPU — Phase 2.

### Какой вариант выбрать?

**Option A (Python в k3s) для MVP → Option C (отдельный ML-сервер) для Phase 2+.**

MVP стартует как Python сервис в текущем k3s кластере (minimal infrastructure change). При подтверждении value и росте нагрузки — вынос на отдельный GPU-сервер.

### 3-5 Prerequisites до старта

1. **OCR benchmark на реальных записях.** Без этого невозможно оценить feasibility. Нужны 50-100 реальных сегментов, тест Tesseract + PaddleOCR, метрика accuracy.

2. **Юридическая проверка.** ФЗ-152, ТК РФ — допустимость OCR/text extraction из записей сотрудников. Без green light — блокер.

3. **Определение бизнес-целей и классификатора.** Какие конкретно действия нужно обнаруживать? Какие сценарии классифицировать? Без этого невозможно проектировать event model.

4. **Capacity assessment.** Текущий disk space на серверах. Запас CPU/RAM в k3s. Текущий rate сегментов. Storage growth projection.

5. **Deployment OpenSearch.** Уже запланирован, но не развёрнут. Для Phase 1 (поиск по OCR тексту) — необходим.

### Решения, которые нельзя принимать преждевременно

- **Выбор OCR engine** — только после benchmark на реальных данных
- **GPU infrastructure** — только после подтверждения CPU-only MVP
- **ML model architecture** — только после наличия labeled data (Phase 2-3)
- **Real-time vs batch** — по умолчанию batch, real-time только при явном бизнес-требовании
- **Event schema finalization** — итеративно, не пытаться спроектировать "идеальную" схему сразу

### Самый разумный следующий шаг

**Phase 0: OCR Proof of Concept.**

1. Скачать 50 сегментов из MinIO (разные устройства, приложения, пользователи).
2. Написать Python-скрипт: FFmpeg frame extraction → Tesseract OCR → JSON output.
3. Вручную оценить quality: процент корректно распознанного текста по приложениям.
4. Измерить performance: секунд на сегмент, CPU/RAM consumption.
5. Результат: Go/No-Go decision + выбор OCR engine + frame sampling strategy.

Это можно сделать за 3-5 дней на локальной машине, без изменений в production.

---

*Документ подготовлен на основе фактического анализа кодовой базы проекта Кадеро (6 сервисов, 35 миграций, 100+ API endpoints, 2 k3s кластера). Все выводы привязаны к текущему состоянию AS-IS. Допущения отмечены явно.*
