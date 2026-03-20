# T-178: Развёртывание промышленного контура на kadero.online

**Тип:** Task (Infrastructure)
**Приоритет:** High
**Статус:** Open
**Дата анализа:** 2026-03-20

---

## 1. Целевая конфигурация

### Инфраструктура

| Роль | Кол-во | vCPU | RAM | Диск | Назначение |
|------|--------|------|-----|------|-----------|
| **App Server** | 2 | 8 | 16 GB | 150 GB HDD | k3s worker: все микросервисы, Kafka, MinIO, OpenSearch |
| **Load Balancer** | 1 | 2-4 | 4-8 GB | 50 GB | nginx/HAProxy, SSL termination, health checks |

### Сетевая схема

```
Internet
    │
    ▼
┌─────────────────────────────────────────────────┐
│  Load Balancer (LB)                              │
│  IP: публичный                                   │
│  DNS: kadero.online → LB IP                      │
│  SSL: Let's Encrypt wildcard                     │
│  nginx reverse proxy                             │
│                                                   │
│  Маршрутизация:                                   │
│  kadero.online/* → App1 / App2 (round-robin)     │
│  Health check: /healthz каждые 10s               │
│                                                   │
│  Rate limiting: 100 req/s per IP                 │
│  Max body: 100MB (для видеосегментов)            │
└──────┬───────────────────┬───────────────────────┘
       │                   │
       ▼                   ▼
┌──────────────┐   ┌──────────────┐
│  App Server 1 │   │  App Server 2 │
│  (app1)       │   │  (app2)       │
│               │   │               │
│  k3s master   │   │  k3s worker   │
│               │   │               │
│  PostgreSQL   │◄─►│  PostgreSQL   │
│  (primary)    │   │  (replica)    │
│               │   │               │
│               │   │               │
│  (S3 → Yandex Object Storage)    │
│               │   │               │
│  Kafka        │   │  Kafka        │
│  (broker 1)   │   │  (broker 2)   │
│               │   │               │
│  OpenSearch   │   │  OpenSearch   │
│  (node 1)     │   │  (node 2)     │
└──────────────┘   └──────────────┘
      │                    │
      └────── VPN / private network ──┘
```

---

## 2. Распределение сервисов по серверам

### App Server 1 (app1) — Primary

| Компонент | Реплик | RAM | CPU | Диск |
|-----------|--------|-----|-----|------|
| **PostgreSQL** (primary) | 1 | 4 GB | 2 cores | 50 GB |
| ~~MinIO~~ | — | — | — | — *(Yandex Object Storage)* |
| **Kafka** (KRaft broker 1) | 1 | 2 GB | 1 core | 10 GB |
| **OpenSearch** (node 1) | 1 | 2 GB | 1 core | 20 GB |
| auth-service | 2 | 512 MB × 2 | 0.5 × 2 | — |
| control-plane | 2 | 512 MB × 2 | 0.5 × 2 | — |
| ingest-gateway | 2 | 512 MB × 2 | 0.5 × 2 | — |
| playback-service | 1 | 256 MB | 0.25 | — |
| search-service | 1 | 256 MB | 0.25 | — |
| web-dashboard | 1 | 128 MB | 0.1 | — |
| **Итого** | | **~13.2 GB** | **~8 cores** | **~130 GB** |

### App Server 2 (app2) — Secondary

| Компонент | Реплик | RAM | CPU | Диск |
|-----------|--------|-----|-----|------|
| **PostgreSQL** (streaming replica) | 1 | 4 GB | 2 cores | 50 GB |
| ~~MinIO~~ | — | — | — | — *(Yandex Object Storage)* |
| **Kafka** (KRaft broker 2) | 1 | 2 GB | 1 core | 10 GB |
| **OpenSearch** (node 2) | 1 | 2 GB | 1 core | 20 GB |
| auth-service | 2 | 512 MB × 2 | 0.5 × 2 | — |
| control-plane | 2 | 512 MB × 2 | 0.5 × 2 | — |
| ingest-gateway | 2 | 512 MB × 2 | 0.5 × 2 | — |
| playback-service | 1 | 256 MB | 0.25 | — |
| search-service | 1 | 256 MB | 0.25 | — |
| web-dashboard | 1 | 128 MB | 0.1 | — |
| **Итого** | | **~13.2 GB** | **~8 cores** | **~130 GB** |

### Load Balancer

| Компонент | RAM | CPU | Диск |
|-----------|-----|-----|------|
| nginx (reverse proxy + SSL) | 256 MB | 0.5 core | — |
| certbot (Let's Encrypt) | 128 MB | 0.1 core | 1 GB |
| Prometheus node_exporter | 64 MB | 0.1 core | — |
| **Итого** | **~500 MB** | **~1 core** | **~1 GB** |

---

## 3. Высокая доступность (HA)

### PostgreSQL: Primary-Replica

```
App1: PostgreSQL Primary (read-write)
        │ streaming replication (async)
        ▼
App2: PostgreSQL Replica (read-only)
```

- **Failover:** Patroni или pg_auto_failover
- **Приложения:** подключаются через connection pool (PgBouncer) или напрямую к primary
- **Read-only запросы** (поиск, отчёты): можно направлять на replica

### MinIO: Distributed mode

```
App1: MinIO node 1 (disk /data/minio)
App2: MinIO node 2 (disk /data/minio)
→ MinIO Distributed: erasure coding, данные реплицируются
→ Потеря одного сервера не приводит к потере данных
```

Минимум 2 ноды × 1 drive = distributed mode с `MINIO_ERASURE_SET_DRIVE_COUNT=2`.

### Kafka: 2 брокера KRaft

```
App1: Kafka broker 1 (controller + broker)
App2: Kafka broker 2 (controller + broker)
→ Replication factor = 2 для всех топиков
→ min.insync.replicas = 1 (допускает потерю 1 ноды)
```

### OpenSearch: 2 ноды

```
App1: OpenSearch node 1 (master-eligible + data)
App2: OpenSearch node 2 (master-eligible + data)
→ index.number_of_replicas = 1
→ Потеря 1 ноды: кластер yellow, но данные доступны
```

### Микросервисы: 2 реплики на разных серверах

```yaml
# k8s anti-affinity: реплики на разных нодах
affinity:
  podAntiAffinity:
    preferredDuringSchedulingIgnoredDuringExecution:
      - weight: 100
        podAffinityTerm:
          topologyKey: kubernetes.io/hostname
          labelSelector:
            matchLabels:
              app: auth-service
```

---

## 4. Load Balancer: nginx конфигурация

### DNS

```
kadero.online     → A    → <LB_IP>
*.kadero.online   → A    → <LB_IP>
```

### SSL

```bash
# Let's Encrypt wildcard (DNS-01 challenge)
certbot certonly --dns-cloudflare \
  -d kadero.online -d '*.kadero.online' \
  --email admin@kadero.online
```

Или: обычный HTTP-01 challenge для `kadero.online`.

### nginx.conf (Load Balancer)

```nginx
upstream app_servers {
    # Round-robin с health checks
    server app1:443 max_fails=3 fail_timeout=30s;
    server app2:443 max_fails=3 fail_timeout=30s;
}

server {
    listen 80;
    server_name kadero.online;
    return 301 https://$host$request_uri;
}

server {
    listen 443 ssl http2;
    server_name kadero.online;

    ssl_certificate     /etc/letsencrypt/live/kadero.online/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/kadero.online/privkey.pem;

    # Security headers
    add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
    add_header X-Frame-Options DENY;
    add_header X-Content-Type-Options nosniff;
    add_header X-XSS-Protection "1; mode=block";

    # Limits
    client_max_body_size 100m;  # для видеосегментов
    proxy_read_timeout 120s;
    proxy_connect_timeout 10s;

    # Rate limiting
    limit_req zone=general burst=50 nodelay;

    location / {
        proxy_pass https://app_servers;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        # WebSocket support (для будущего real-time)
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
    }

    # Health check endpoint (не проксировать)
    location /healthz {
        return 200 'OK';
    }
}

# Rate limit zone
limit_req_zone $binary_remote_addr zone=general:10m rate=100r/s;
```

---

## 5. k3s кластер: два сервера

### Установка

```bash
# App1: k3s server (master)
curl -sfL https://get.k3s.io | sh -s - server \
  --cluster-init \
  --tls-san kadero.online \
  --tls-san app1.kadero.online \
  --disable traefik  # используем ingress через LB

# App2: k3s agent (worker) — подключается к app1
curl -sfL https://get.k3s.io | sh -s - agent \
  --server https://app1:6443 \
  --token <node-token>
```

### Namespace

```bash
kubectl create namespace prod-kadero
```

### k8s manifests

Все деплойменты с `replicas: 2` + anti-affinity. Сервисы:

| Deployment | Replicas | CPU request | Memory request |
|-----------|----------|-------------|----------------|
| auth-service | 2 | 500m | 512Mi |
| control-plane | 2 | 500m | 512Mi |
| ingest-gateway | 2 | 500m | 512Mi |
| playback-service | 1 | 250m | 256Mi |
| search-service | 1 | 250m | 256Mi |
| web-dashboard | 1 | 100m | 128Mi |

---

## 6. PostgreSQL: production setup

### На App1 (primary)

```bash
# /etc/postgresql/16/main/postgresql.conf
listen_addresses = '*'
port = 5432
max_connections = 200
shared_buffers = 4GB          # 25% RAM
effective_cache_size = 12GB   # 75% RAM
work_mem = 32MB
maintenance_work_mem = 512MB
wal_level = replica
max_wal_senders = 5
wal_keep_size = 1GB

# pg_hba.conf: разрешить репликацию с app2
host replication replicator app2_ip/32 scram-sha-256
host all prg_app app2_ip/32 scram-sha-256
```

### На App2 (replica)

```bash
# Инициализация replica
pg_basebackup -h app1 -U replicator -D /var/lib/postgresql/16/main -Fp -Xs -R
# -R создаёт standby.signal + primary_conninfo в postgresql.auto.conf

systemctl start postgresql
```

### Базы данных

```sql
CREATE DATABASE kadero_prod;
CREATE USER kadero_app WITH ENCRYPTED PASSWORD '<secure_password>';
GRANT ALL ON DATABASE kadero_prod TO kadero_app;
GRANT CREATE ON SCHEMA public TO kadero_app;
```

### Бэкапы

```bash
# Ежедневный pg_dump (cron на app1)
0 3 * * * pg_dump -Fc kadero_prod > /backup/kadero_prod_$(date +\%Y\%m\%d).dump
# Retention: 30 дней
find /backup -name "kadero_prod_*.dump" -mtime +30 -delete
```

---

## 7. S3: Yandex Object Storage (вместо MinIO)

На продакшене вместо локального MinIO используем **Yandex Object Storage** (S3-совместимый). Это снимает ограничение в 150 GB диска и обеспечивает масштабируемое хранение видеосегментов.

### Почему не MinIO

- 150 GB HDD × 2 сервера — при 1000 агентах хватит на ~1 час записей
- Yandex Object Storage: безлимитный объём, оплата по факту использования
- S3 API полностью совместим — никаких изменений в коде (ingest-gateway, playback-service уже работают через S3 API)

### Настройка Yandex Object Storage

```bash
# 1. Создать сервисный аккаунт в Yandex Cloud
yc iam service-account create --name kadero-s3

# 2. Назначить роль storage.editor
yc resource-manager folder add-access-binding <folder-id> \
  --role storage.editor \
  --subject serviceAccount:<sa-id>

# 3. Создать статический ключ доступа
yc iam access-key create --service-account-name kadero-s3
# → access_key_id: YC...
# → secret: ...
```

### Bucket

```bash
# Создать bucket через AWS CLI (S3-совместимый)
aws s3 mb s3://kadero-segments \
  --endpoint-url https://storage.yandexcloud.net

# Lifecycle policy: удалять сегменты старше 90 дней
aws s3api put-bucket-lifecycle-configuration \
  --bucket kadero-segments \
  --endpoint-url https://storage.yandexcloud.net \
  --lifecycle-configuration '{
    "Rules": [{
      "ID": "expire-old-segments",
      "Status": "Enabled",
      "Expiration": {"Days": 90},
      "Filter": {"Prefix": ""}
    }]
  }'
```

### Конфигурация в k8s

```yaml
# k8s Secret
apiVersion: v1
kind: Secret
metadata:
  name: yandex-s3-credentials
  namespace: prod-kadero
stringData:
  S3_ENDPOINT: "https://storage.yandexcloud.net"
  S3_ACCESS_KEY: "YC..."
  S3_SECRET_KEY: "..."
  S3_BUCKET: "kadero-segments"
  S3_REGION: "ru-central1"
```

```yaml
# Deployment env для ingest-gateway и playback-service
env:
  - name: S3_ENDPOINT
    valueFrom:
      secretKeyRef:
        name: yandex-s3-credentials
        key: S3_ENDPOINT
  - name: S3_ACCESS_KEY
    valueFrom:
      secretKeyRef:
        name: yandex-s3-credentials
        key: S3_ACCESS_KEY
  - name: S3_SECRET_KEY
    valueFrom:
      secretKeyRef:
        name: yandex-s3-credentials
        key: S3_SECRET_KEY
  - name: S3_BUCKET
    valueFrom:
      secretKeyRef:
        name: yandex-s3-credentials
        key: S3_BUCKET
  - name: S3_REGION
    valueFrom:
      secretKeyRef:
        name: yandex-s3-credentials
        key: S3_REGION
```

### Presigned URLs

Текущая логика presign в ingest-gateway уже генерирует S3-совместимые presigned PUT URLs. Единственное изменение — endpoint:
- Было: `http://minio:9000` (внутренний k8s)
- Стало: `https://storage.yandexcloud.net` (публичный Yandex S3)

Агент загружает сегменты напрямую в Yandex S3 через presigned URL — трафик не проходит через наши серверы.

### Playback

playback-service генерирует presigned GET URLs для HLS-воспроизведения. Браузер скачивает сегменты напрямую из Yandex S3.

### Стоимость (оценка)

| Параметр | При 100 агентах | При 1000 агентов |
|----------|----------------|-----------------|
| Объём/день | ~330 GB | ~3.3 TB |
| Объём/месяц (retention 90 дней) | ~30 TB | ~300 TB |
| Хранение (₽2.01/GB/мес Standard) | ~60 000 ₽/мес | ~600 000 ₽/мес |
| PUT запросы (~1/мин/агент) | ~4.3M/мес × ₽0.0112 = ~48 ₽ | ~43M/мес = ~480 ₽ |
| GET запросы (playback) | Незначительно | Незначительно |

**Оптимизация стоимости:**
- **Cold storage** для сегментов старше 30 дней (₽1.01/GB/мес)
- **Retention 30 дней** вместо 90 — уменьшает объём в 3 раза
- **Сжатие:** H.264 low quality (уже используется) — ~2.3 MB/мин/агент

### Что НЕ нужно на серверах

- ~~MinIO~~ — не нужен
- Freed: ~50 GB disk + 1 GB RAM на каждом сервере
- Freed: ~1 CPU core (MinIO I/O)

---

## 8. Kafka: 2 брокера KRaft

```yaml
# docker-compose на каждом сервере
# App1: broker.id=1, App2: broker.id=2
environment:
  KAFKA_NODE_ID: 1  # или 2
  KAFKA_PROCESS_ROLES: 'broker,controller'
  KAFKA_CONTROLLER_QUORUM_VOTERS: '1@app1:9093,2@app2:9093'
  KAFKA_LISTENERS: 'PLAINTEXT://:9092,CONTROLLER://:9093'
  KAFKA_INTER_BROKER_LISTENER_NAME: PLAINTEXT
  KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 2
  KAFKA_DEFAULT_REPLICATION_FACTOR: 2
  KAFKA_MIN_INSYNC_REPLICAS: 1
```

Топики с replication-factor=2:
- `segments.confirmed`
- `commands.issued`
- `device.events`
- `segments.written`
- `webhooks.trigger`

---

## 9. Мониторинг

### Stack

| Компонент | Где | Назначение |
|-----------|-----|-----------|
| Prometheus | App1 (или LB) | Метрики всех сервисов |
| Grafana | App1 (или LB) | Дашборды |
| Loki | App1 | Агрегация логов |
| AlertManager | App1 | Алертинг (Telegram, email) |

### Алерты

| Алерт | Условие | Severity |
|-------|---------|----------|
| Service down | Pod not ready > 2 min | Critical |
| PostgreSQL replica lag | lag > 30s | Warning |
| Disk usage > 80% | Node disk > 80% | Warning |
| Disk usage > 90% | Node disk > 90% | Critical |
| Kafka consumer lag | lag > 1000 messages | Warning |
| MinIO offline node | node unreachable > 5 min | Critical |
| SSL cert expiry | < 14 days | Warning |
| High memory | > 90% used | Warning |

### Health checks (LB → App servers)

```
GET /api/v1/health → 200 (auth-service)
GET /api/cp/v1/health → 200 (control-plane)
GET /api/ingest/v1/health → 200 (ingest-gateway)
```

---

## 10. Безопасность

### Сеть

- LB: публичный IP, только порты 80 (→301→443) и 443
- App1, App2: **только приватная сеть** (не публичный IP)
- Между App1-App2: VPN или VLAN
- SSH: только через LB (jump host) или VPN
- Firewall: UFW/iptables — блокировать всё кроме необходимого

### Секреты

| Секрет | Хранение | Используется в |
|--------|----------|----------------|
| DB password | k8s Secret | auth, cp, ingest, playback, search |
| JWT secret | k8s Secret | auth-service |
| MinIO credentials | k8s Secret | ingest-gateway, playback |
| Token encryption key | k8s Secret | auth-service |
| SMTP password | k8s Secret | auth-service |
| OAuth client secrets | k8s Secret | auth-service |

```bash
kubectl -n prod-kadero create secret generic kadero-secrets \
  --from-literal=DB_PASSWORD='<secure>' \
  --from-literal=JWT_SECRET='<secure>' \
  --from-literal=MINIO_ROOT_PASSWORD='<secure>' \
  --from-literal=TOKEN_ENCRYPTION_KEY='<secure>'
```

### SSL/TLS

- HTTPS everywhere (LB → internet)
- Внутри кластера: HTTP (trusted network) или mTLS (если paranoid)
- PostgreSQL: `sslmode=require` для подключений

---

## 11. CI/CD pipeline (будущее)

```
Git push → GitHub Actions:
  1. Build Java services (Maven)
  2. Build frontend (Vite)
  3. Build Docker images
  4. Push to container registry (GitHub Packages / Docker Hub)
  5. Deploy to prod (kubectl apply / ArgoCD)
```

Пока: ручной деплой через скрипт (аналогично test-стейджингу).

---

## 12. План развёртывания (checklist)

### Этап 1: Подготовка серверов

- [ ] Заказать 3 сервера (2 × 8vCPU/16GB/150GB + 1 × LB)
- [ ] Настроить приватную сеть между app1 и app2
- [ ] Установить Ubuntu 22.04 LTS на все серверы
- [ ] Настроить firewall (UFW)
- [ ] Настроить SSH (только ключи, без паролей)

### Этап 2: DNS и SSL

- [ ] Привязать домен kadero.online к IP LB
- [ ] Настроить Let's Encrypt на LB
- [ ] Проверить HTTPS доступ

### Этап 3: Инфраструктурные компоненты

- [ ] PostgreSQL primary на app1 + replica на app2
- [ ] MinIO distributed (app1 + app2)
- [ ] Kafka 2 брокера (app1 + app2)
- [ ] OpenSearch 2 ноды (app1 + app2)
- [ ] k3s cluster (app1 master + app2 worker)

### Этап 4: Микросервисы

- [ ] Создать namespace `prod-kadero`
- [ ] Создать k8s secrets
- [ ] Деплой Flyway миграций (V1-V45)
- [ ] Деплой auth-service (2 реплики)
- [ ] Деплой control-plane (2 реплики)
- [ ] Деплой ingest-gateway (2 реплики)
- [ ] Деплой playback-service (1 реплика)
- [ ] Деплой search-service (1 реплика)
- [ ] Деплой web-dashboard (1 реплика)

### Этап 5: Load Balancer

- [ ] Настроить nginx reverse proxy на LB
- [ ] Настроить health checks
- [ ] Настроить rate limiting
- [ ] Проверить failover (остановить app1 → трафик идёт на app2)

### Этап 6: Мониторинг

- [ ] Prometheus + Grafana
- [ ] Loki для логов
- [ ] AlertManager (Telegram)
- [ ] Dashboard: сервисы, PostgreSQL, Kafka, MinIO, диски

### Этап 7: Smoke-тест

- [ ] Регистрация нового пользователя (OAuth + email)
- [ ] Создание тенанта
- [ ] Регистрация агента (токен)
- [ ] Запись экрана → сегменты в MinIO → подтверждение
- [ ] Поиск записей → OpenSearch
- [ ] Воспроизведение → HLS → браузер
- [ ] Dashboard: метрики отображаются

### Этап 8: Продуктивная эксплуатация

- [ ] Бэкапы PostgreSQL (ежедневно, retention 30 дней)
- [ ] Мониторинг дисков (алерт на 80%)
- [ ] Ротация логов
- [ ] Документация: runbook для команды

---

## 13. Оценка ёмкости

### При 1000 одновременных агентов

| Метрика | Значение | Расчёт |
|---------|----------|--------|
| Heartbeat | 33 req/s | 1000 агентов / 30s |
| Сегменты (presign+PUT+confirm) | ~17 req/s | 1000 × 1 seg/min / 60 |
| Focus intervals | ~33 batch/30s | 1000 / 30s |
| Размер сегментов | ~2.3 MB × 1000/min = 2.3 GB/min | При 720p low quality |
| Дисковое пространство MinIO | ~3.3 TB/день | 2.3 GB/min × 60 × 24 |
| PostgreSQL rows/день | ~1.5M segments + ~5M activity | |

### Bottleneck

- **S3 (Yandex Object Storage)** — безлимитный объём, bottleneck снят. Оплата по факту.
- **PostgreSQL** — при 1000 агентах нагрузка умеренная (33 heartbeat/s ≈ 33 TPS)
- **Kafka** — минимальная нагрузка (fire-and-forget)
- **Диск серверов** — без MinIO: PostgreSQL (~50 GB) + Kafka (~10 GB) + OpenSearch (~20 GB) + OS = ~100 GB. 150 GB HDD достаточно с запасом.

### S3 стоимость

При 1000 агентах, retention 90 дней, Yandex Object Storage Standard:
- Объём: ~300 TB → **~600 000 ₽/мес**
- Оптимизация: retention 30 дней → ~100 TB → **~200 000 ₽/мес**
- Cold storage (30+ дней): **~100 000 ₽/мес**

---

## 14. Отличия от test-стейджинга

| Параметр | Test (shepaland-cloud) | Prod (kadero.online) |
|----------|----------------------|---------------------|
| Серверов | 1 | 3 (2 app + 1 LB) |
| Реплик сервисов | 1 | 2 |
| PostgreSQL | Single node | Primary + Replica |
| MinIO | Single node | Distributed (2 nodes) |
| Kafka | 1 broker | 2 brokers |
| OpenSearch | 1 node | 2 nodes |
| SSL | Traefik ACME | nginx + Let's Encrypt |
| Балансировка | Нет | nginx round-robin |
| Мониторинг | Нет | Prometheus + Grafana |
| Бэкапы | Нет | Ежедневный pg_dump |
| Домен | services-test.shepaland.ru | kadero.online |
