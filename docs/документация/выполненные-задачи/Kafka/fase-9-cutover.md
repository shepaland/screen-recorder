# Фаза 9 (LAST): Переключение маршрутов

> **ЕДИНСТВЕННАЯ фаза, которая меняет поведение для конечных пользователей.**
> Выполняется ТОЛЬКО после полной верификации всех предыдущих фаз.
> Rollback: убрать location-блоки из nginx.conf → передеплоить web-dashboard.

---

## Цель

Подключить nginx-маршруты к search-service и playback-service. Переключить web-dashboard API base URL на relative paths. Финальный cutover.

## Предусловия

**ВСЕ** предыдущие фазы завершены и верифицированы:
- Фаза 1: Kafka Running
- Фаза 2: Dual-write включён
- Фаза 3: segment-writer — lag = 0, 24ч верификация
- Фаза 4: kafka-only confirm активен, auto-fallback работает
- Фаза 5: search-service Running, API работает через port-forward
- Фаза 6: playback-service Running, HLS воспроизводится через port-forward
- Фаза 7: Webhooks — CRUD + delivery работают
- Фаза 8: UI компоненты собраны, `npm run build` проходит

---

## Pre-flight Checklist

**Выполнить ДО начала cutover. Все пункты = PASS.**

### Инфраструктура
- [ ] Kafka pod Running, все 5 topics, retention корректный
- [ ] segment-writer consumer: lag = 0, batch writes стабильны
- [ ] OpenSearch pod Running, cluster health green/yellow
- [ ] search-service pod Running, `search-indexer` consumer lag = 0
- [ ] playback-service pod Running, `/actuator/health` = UP
- [ ] Network policies: все egress/ingress работают

### Kafka-only confirm
- [ ] `KAFKA_CONFIRM_MODE=kafka-only` активен
- [ ] Confirm latency p95 < 5ms
- [ ] Данные в PostgreSQL через 1-5 сек
- [ ] Данные в OpenSearch через 2-10 сек
- [ ] Auto-fallback при Kafka down → sync confirm работает

### Функциональность (port-forward)
- [ ] `GET /api/v1/search/segments?tenant_id=...` → результаты
- [ ] Tenant A НЕ видит данные tenant B
- [ ] `GET /api/v1/playback/sessions/{id}/playlist.m3u8` → валидный M3U8
- [ ] HLS воспроизводится (VLC/ffplay через port-forward)
- [ ] Backfill существующих сегментов в OpenSearch завершён
- [ ] Webhook delivery работает (тестовый endpoint получает POST)

### Graceful Degradation
- [ ] Kafka down → confirm() auto-fallback на sync
- [ ] segment-writer down → Kafka buffer, дочитает при рестарте
- [ ] OpenSearch down → search-service 503, остальные сервисы ОК
- [ ] search-service down → остальные страницы dashboard работают
- [ ] playback-service down → только плеер недоступен

### Регрессия текущего функционала
- [ ] Агент: heartbeat работает
- [ ] Агент: presign + confirm цикл (202 Accepted)
- [ ] Dashboard: устройства, записи, аналитика отображаются
- [ ] Авторизация: login/logout/token refresh
- [ ] Device tokens CRUD
- [ ] Recording toggle
- [ ] Справочники (catalogs)

---

## Задачи

### KFK-080: Pre-flight checklist execution

**Что сделать:**

Пройти все пункты чеклиста выше. Зафиксировать результаты. Если хотя бы один FAIL — **НЕ продолжать**.

**Критерий приёмки:** 100% PASS.

---

### KFK-081: Nginx — добавить route `/api/search/`

**Что сделать:**

В `deploy/docker/web-dashboard/nginx.conf` добавить location **перед** `location /api/`:

```nginx
    # Search service (НОВОЕ)
    location /api/search/ {
        proxy_pass http://search-service:8083/api/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
```

**ВАЖНО:** Порядок location-блоков в nginx имеет значение. `/api/search/` должен быть **до** `/api/`, иначе catch-all `/api/` перехватит запрос.

**Файлы:** `deploy/docker/web-dashboard/nginx.conf`

---

### KFK-082: Nginx — добавить route `/api/playback/`

**Что сделать:**

```nginx
    # Playback service (НОВОЕ)
    location /api/playback/ {
        proxy_pass http://playback-service:8082/api/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        # Для HLS streaming
        proxy_buffering off;
        proxy_cache off;
    }
```

**Файлы:** `deploy/docker/web-dashboard/nginx.conf`

---

### KFK-083: Web-dashboard — API base URL → relative

**Что сделать:**

Убрать port-forward URLs из API-клиентов, использовать relative paths:

```typescript
// src/api/search.ts — base URL = '' (relative)
// Запрос уйдёт на /api/search/... → nginx → search-service:8083

// src/api/playback.ts — аналогично
// /api/playback/... → nginx → playback-service:8082
```

Если использовался env variable для base URL — установить в production:
```
VITE_SEARCH_API_URL=/api/search
VITE_PLAYBACK_API_URL=/api/playback
```

**Файлы:**
- `web-dashboard/src/api/search.ts`
- `web-dashboard/src/api/playback.ts`
- `web-dashboard/.env.production` (если используется)

---

### KFK-084: Пересборка + деплой web-dashboard

**Что сделать:**

```bash
# На shepaland-cloud
cd /home/shepelkina/screen-recorder

# 1. Скопировать обновлённые файлы (nginx.conf + web-dashboard src)
rsync -avz web-dashboard/ shepelkina@shepaland-cloud:/home/shepelkina/screen-recorder/web-dashboard/
rsync -avz deploy/docker/web-dashboard/ shepelkina@shepaland-cloud:/home/shepelkina/screen-recorder/deploy/docker/web-dashboard/

# 2. Docker build (на сервере)
ssh shepaland-cloud
cd /home/shepelkina/screen-recorder
docker build --no-cache -t prg-web-dashboard:latest -f deploy/docker/web-dashboard/Dockerfile .

# 3. k3s import
sudo k3s ctr images remove docker.io/library/prg-web-dashboard:latest 2>/dev/null
docker save prg-web-dashboard:latest | sudo k3s ctr images import -

# 4. Rollout restart
sudo k3s kubectl -n test-screen-record rollout restart deployment/web-dashboard
sudo k3s kubectl -n test-screen-record rollout status deployment/web-dashboard
```

**Критерий приёмки:** Pod Running, новая версия (проверить image ID).

---

### KFK-085: Smoke-тест после cutover

**Что сделать:**

Открыть браузер: `https://services-test.shepaland.ru/screenrecorder`

**Новые функции:**
- [ ] Перейти на `/search` → страница поиска загружается
- [ ] Ввести запрос → результаты отображаются
- [ ] Кликнуть по записи → `/recordings/:sessionId` → плеер загружается
- [ ] Нажать Play → видео воспроизводится
- [ ] Перейти в `/settings/webhooks` → список подписок отображается
- [ ] Создать webhook подписку → success

**Регрессия (старые функции):**
- [ ] Login/logout работает
- [ ] Dashboard загружается (устройства, аналитика)
- [ ] Справочники (catalogs) работают
- [ ] Device tokens — list/create/edit/delete
- [ ] Recording toggle работает
- [ ] Агент: heartbeat + presign + confirm проходят (проверить логи)

**Критерий приёмки:** Все пункты PASS.

---

### KFK-086: Мониторинг 24ч после cutover

**Что сделать:**

Наблюдать в течение 24 часов:

| Метрика | Норма | Алерт |
|---------|-------|-------|
| Kafka consumer lag (segment-writer) | < 100 | > 1000 |
| Kafka consumer lag (search-indexer) | < 100 | > 1000 |
| Confirm latency p95 | < 5ms | > 50ms |
| Search latency p95 | < 500ms | > 2000ms |
| HTTP 5xx rate | < 0.1% | > 1% |
| PostgreSQL connections | < 80% pool | > 90% |

**При аномалиях — план rollback:**

1. **Откатить nginx routes** (если search/playback сломаны):
   - Убрать location `/api/search/` и `/api/playback/` из nginx.conf
   - Rebuild + redeploy web-dashboard
   - UI: новые страницы перестанут работать, старые ОК

2. **Откатить kafka-only confirm** (если confirm сломан):
   - ConfigMap `KAFKA_CONFIRM_MODE=sync`
   - Rollout restart ingest-gateway
   - Confirm возвращается к sync DB write

3. **Полный rollback** (если всё плохо):
   - `KAFKA_DUAL_WRITE=false`, `KAFKA_CONFIRM_MODE=sync`
   - Убрать nginx routes
   - Rebuild + redeploy всё
   - Система = AS-IS (до начала работ)

---

## Итоговый порядок location-блоков в nginx.conf

```nginx
server {
    listen 80;

    # ... security headers ...

    # 1. Swagger docs (exact prefix match)
    location ^~ /docs/auth/  { proxy_pass http://auth-service:8081/; }
    location ^~ /docs/cp/    { proxy_pass http://control-plane:8080/; }
    location ^~ /docs/ingest/ { proxy_pass http://ingest-gateway:8084/; }

    # 2. S3 segments (presigned uploads)
    location /prg-segments/ { proxy_pass http://minio:9000; }

    # 3. API routes (ПОРЯДОК ВАЖЕН: от длинного к короткому)
    location /api/search/   { proxy_pass http://search-service:8083/api/; }   # НОВОЕ
    location /api/playback/ { proxy_pass http://playback-service:8082/api/; } # НОВОЕ
    location /api/cp/       { proxy_pass http://control-plane:8080/api/; }
    location /api/ingest/   { proxy_pass http://ingest-gateway:8084/api/; }
    location /api/          { proxy_pass http://auth-service:8081; }

    # 4. SPA fallback
    location / {
        root /usr/share/nginx/html;
        try_files $uri $uri/ /index.html;
    }
}
```

---

## Чеклист завершения фазы

- [ ] Pre-flight checklist = 100% PASS
- [ ] Nginx routes `/api/search/` и `/api/playback/` добавлены
- [ ] Web-dashboard пересобран с relative API URLs
- [ ] Smoke-тест: поиск, плеер, webhooks — всё работает
- [ ] Регрессия: все старые функции работают
- [ ] Мониторинг 24ч: consumer lag, latency, error rate в норме
- [ ] **План rollback задокументирован и проверен**
