# Kafka Integration — План фаз

> **Системная аналитика:** [`kafka-integration-plan.md`](../kafka-integration-plan.md) (v3.0)

## Фазы

| Фаза | Файл | Задачи | Влияние на систему |
|------|------|--------|-------------------|
| 1. Kafka инфраструктура | [`fase-1-kafka-infrastructure.md`](fase-1-kafka-infrastructure.md) | KFK-001..007 | НОЛЬ |
| 2. Dual-Write | [`fase-2-dual-write.md`](fase-2-dual-write.md) | KFK-010..016 | НОЛЬ (feature flag) |
| 3. segment-writer consumer | [`fase-3-segment-writer.md`](fase-3-segment-writer.md) | KFK-020..026 | НОЛЬ (параллельный path) |
| 4. Kafka-only confirm | [`fase-4-kafka-only-confirm.md`](fase-4-kafka-only-confirm.md) | KFK-030..036 | **Confirm → 202 Accepted, eventual consistency 1-5 сек** |
| 5. search-service + OpenSearch | [`fase-5-search-service.md`](fase-5-search-service.md) | KFK-040..048 | НОЛЬ (port-forward) |
| 6. playback-service (HLS) | [`fase-6-playback-service.md`](fase-6-playback-service.md) | KFK-050..055 | НОЛЬ (port-forward) |
| 7. Webhook Worker | [`fase-7-webhook-worker.md`](fase-7-webhook-worker.md) | KFK-060..065 | НОЛЬ |
| 8. Web-dashboard UI | [`fase-8-web-dashboard-ui.md`](fase-8-web-dashboard-ui.md) | KFK-070..075 | НОЛЬ (новые страницы) |
| 9. Переключение маршрутов **(LAST)** | [`fase-9-cutover.md`](fase-9-cutover.md) | KFK-080..086 | **Единственный шаг, меняющий UX** |

## Граф зависимостей

```
  Фаза 1 → Фаза 2 → Фаза 3 → Фаза 4 (Kafka-only confirm)
                 │         │
                 │         ├──→ Фаза 5 (Search) ──┐
                 │         │                       ├──→ Фаза 8 (UI) ──→ Фаза 9 (LAST)
                 │         └──→ Фаза 6 (Playback)─┘
                 └──→ Фаза 7 (Webhooks) ──────────┘
```

## Итого задач

| Фаза | Кол-во задач |
|------|-------------|
| 1 | 7 |
| 2 | 7 |
| 3 | 7 |
| 4 | 7 |
| 5 | 9 |
| 6 | 6 |
| 7 | 6 |
| 8 | 6 |
| 9 | 7 |
| **Итого** | **62** |
