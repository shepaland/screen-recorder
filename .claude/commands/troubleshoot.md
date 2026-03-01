Используй субагента devops. Разберись с проблемой: $ARGUMENTS

Порядок диагностики:
1. kubectl -n <namespace> get pods — статус подов
2. kubectl -n <namespace> describe pod <pod> — events, conditions, причина рестарта
3. kubectl -n <namespace> logs <pod> --tail=100 — последние логи
4. kubectl -n <namespace> logs <pod> --previous — логи предыдущего контейнера (если CrashLoopBackOff)
5. kubectl -n <namespace> top pod — потребление CPU/RAM
6. kubectl -n <namespace> get events --sort-by=.lastTimestamp — последние события
7. Проверь connectivity: curl из пода к PostgreSQL (172.17.0.1:5432), MinIO, NATS, OpenSearch
8. Проверь health endpoint: /actuator/health
9. Проверь конфигурацию: ConfigMap, Secret — все переменные на месте

Выдай: root cause, затронутые компоненты, шаги исправления.
