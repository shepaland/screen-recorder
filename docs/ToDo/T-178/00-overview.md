# T-178: Декомпозиция — Промышленный контур kadero.online

**Родительская задача:** `docs/ToDo/T-178-промышленный-контур-kadero-online.md`

## Подзадачи

| # | Задача | Зависит от | Файл |
|---|--------|------------|------|
| T-178.1 | Подготовка серверов: ОС, сеть, firewall, SSH | — | `01-серверы.md` |
| T-178.2 | DNS + SSL (kadero.online, Let's Encrypt) | T-178.1 | `02-dns-ssl.md` |
| T-178.3 | PostgreSQL primary + replica | T-178.1 | `03-postgresql.md` |
| T-178.4 | MinIO distributed на отдельных дисках | T-178.1 | `04-minio.md` |
| T-178.5 | Kafka 2 брокера (KRaft) | T-178.1 | `05-kafka.md` |
| T-178.6 | OpenSearch 2 ноды | T-178.1 | `06-opensearch.md` |
| T-178.7 | k3s кластер (master + worker) | T-178.1 | `07-k3s.md` |
| T-178.8 | Load Balancer: nginx reverse proxy | T-178.2, T-178.7 | `08-load-balancer.md` |
| T-178.9 | Деплой микросервисов (secrets + deployments) | T-178.3–T-178.7 | `09-деплой-сервисов.md` |
| T-178.10 | Мониторинг: Prometheus + Grafana + Loki + AlertManager | T-178.9 | `10-мониторинг.md` |
| T-178.11 | Smoke-тест промышленного контура | T-178.8–T-178.9 | `11-smoke-тест.md` |

## Порядок реализации

```
T-178.1 (серверы)
    │
    ├── T-178.2 (DNS + SSL)
    ├── T-178.3 (PostgreSQL)      ──┐
    ├── T-178.4 (MinIO)           ──┤
    ├── T-178.5 (Kafka)           ──┤── параллельно
    ├── T-178.6 (OpenSearch)      ──┤
    └── T-178.7 (k3s)            ──┘
                                    │
                              T-178.8 (Load Balancer)
                                    │
                              T-178.9 (Деплой сервисов)
                                    │
                              T-178.10 (Мониторинг)
                                    │
                              T-178.11 (Smoke-тест)
```

## Ответственные

| Роль | Задачи |
|------|--------|
| DevOps | T-178.1–T-178.8, T-178.10 |
| Backend | T-178.9 (secrets, env, Flyway) |
| QA | T-178.11 |
