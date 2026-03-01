Используй субагента devops. Задеплой на стейджинг: $ARGUMENTS

Ожидаемый формат аргумента: "<service> <stage>" или "<stage>" (все сервисы).
Примеры: "auth-service dev", "dev", "all test", "control-plane prod"

Порядок работы:
1. Определи целевой namespace: dev → dev-screen-record, test → test-screen-record, prod → prod-screen-record
2. Собери Docker-образ(ы): docker build -t prg-<service>:latest
3. Для dev/test — деплой автоматически
4. Для prod — СТОП. Покажи пользователю: какие образы, какие версии, какой namespace. Жди явного подтверждения.
5. Примени манифесты: kubectl -n <namespace> apply -f deploy/k8s/<service>/
6. Дождись rollout: kubectl -n <namespace> rollout status deployment/<service>
7. Проверь pods: kubectl -n <namespace> get pods -l app=<service>
8. Проверь health: curl <service-url>/actuator/health
9. Для dev: прогони smoke-тесты
10. Покажи итог: статус pods, health check, логи последних 20 строк
