Используй субагента devops. Покажи статус стейджинга: $ARGUMENTS

Если аргумент не указан — показать все три стейджинга.
Допустимые значения: dev, test, prod, all.

Для каждого namespace собери:
1. kubectl -n <ns> get pods -o wide — статус всех подов, ноды, рестарты
2. kubectl -n <ns> get svc — сервисы и порты
3. kubectl -n <ns> top pod — CPU/RAM потребление
4. Health checks: curl каждого сервиса /actuator/health
5. kubectl -n <ns> get events --sort-by=.lastTimestamp | tail -10 — последние события
6. Версии образов: kubectl -n <ns> get deployment -o jsonpath='{range .items[*]}{.metadata.name}: {.spec.template.spec.containers[0].image}{"\n"}{end}'

Выдай сводную таблицу: сервис | статус | pods ready | CPU | RAM | image version | health.
