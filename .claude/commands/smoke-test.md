Используй субагента qa-tester. Прогони smoke-тесты на dev-стейджинге для: $ARGUMENTS

Если аргумент не указан — smoke всех сервисов.

Чеклист smoke-тестов для каждого сервиса:

**auth-service:**
- POST /api/v1/auth/login — логин с тестовыми credentials → 200 + JWT
- POST /api/v1/auth/refresh — обновление токена → 200
- GET /api/v1/roles — список ролей с JWT → 200

**control-plane:**
- GET /api/v1/devices — список устройств → 200
- GET /api/v1/policies — список политик → 200
- GET /api/v1/presets — список пресетов → 200

**ingest-gateway:**
- POST /api/v1/segments/presign — получение upload URL → 200
- /actuator/health → 200

**playback-service:**
- /actuator/health → 200

**search-service:**
- POST /api/v1/search — пустой поиск → 200 + пустой результат
- /actuator/health → 200

**web-dashboard:**
- GET / → 200 + HTML
- GET /assets/ → статика отдаётся

**Инфраструктура:**
- PostgreSQL: psql -h 172.17.0.1 -U prg_dev -c "SELECT 1"
- MinIO: curl http://minio:9000/minio/health/live
- NATS: nats server check connection
- OpenSearch: curl http://opensearch:9200/_cluster/health

Выдай: таблица сервис | endpoint | статус | время ответа | результат (PASS/FAIL).
