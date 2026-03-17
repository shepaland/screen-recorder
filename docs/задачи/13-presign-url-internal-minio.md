# 13. Presign URL указывает на внутренний MinIO — агент не может загрузить сегменты

## Описание дефекта

После установки обновлённого агента (v2026.3.17.1) запись не попадает на сервер. Агент:
- Создаёт сессию — OK (`POST /sessions` → 200, session active)
- Получает presign URL — OK (`POST /presign` → 200)
- **Не может загрузить в S3** — presign URL содержит `http://minio:9000/...` (внутренний k8s DNS)
- Не вызывает confirm — upload не прошёл, confirm невозможен
- **Не показывает ошибку** — S3 upload failure ловится общим `catch`, `return false`, сегмент уходит в pending retry

### Доказательства

**Presign response (от сервера):**
```json
{
  "segment_id": "e878caf3-...",
  "upload_url": "http://minio:9000/prg-segments/386597e2-.../00999.mp4?X-Amz-Algorithm=..."
}
```

**Проблема:** `http://minio:9000` — Service DNS k8s кластера. Агент на Windows-машине `192.168.1.135` не может резолвить `minio`. DNS lookup fails → connection timeout → retry через 5 секунд → бесконечный цикл.

**БД:** сегменты в статусе `uploaded` (presign создал запись), `segment_count = 0` (confirm не вызван), файлы в S3 отсутствуют.

## Корневая причина

`ingest-gateway` генерирует presigned URL через AWS SDK S3Presigner с endpoint `S3_ENDPOINT=http://minio:9000`. Presigner использует этот endpoint для подписи и формирования URL. Результат — URL с внутренним hostname.

```java
// S3Config.java
@Bean
public S3Presigner s3Presigner() {
    return S3Presigner.builder()
        .endpointOverride(URI.create(endpoint))  // http://minio:9000
        ...
}
```

**Почему раньше работало:** До Kafka-интеграции и текущих изменений использовалась другая версия ingest-gateway с configmap, содержащим `S3_ENDPOINT` через proxy. Или presign URL подменялся на фронте. Нужно проверить историю configmap.

## Решение

### Вариант A: Отдельный S3_PRESIGN_ENDPOINT (рекомендуется)

Добавить переменную окружения `S3_PRESIGN_ENDPOINT` — внешний URL MinIO, доступный агентам. Использовать его только для presign, оставить `S3_ENDPOINT` для server-to-server операций.

**Для test-стейджинга:** агенты обращаются через nginx proxy:
```
S3_PRESIGN_ENDPOINT=https://services-test.shepaland.ru/screenrecorder/prg-segments
```

**Изменения:**

1. **application.yml (ingest-gateway):**
```yaml
prg:
  s3:
    endpoint: ${S3_ENDPOINT:http://localhost:9000}
    presign-endpoint: ${S3_PRESIGN_ENDPOINT:${S3_ENDPOINT:http://localhost:9000}}
```

2. **S3Config.java:** создать второй S3Presigner bean с presign-endpoint
```java
@Bean("presignerForAgents")
public S3Presigner s3PresignerForAgents(
        @Value("${prg.s3.presign-endpoint}") String presignEndpoint, ...) {
    return S3Presigner.builder()
        .endpointOverride(URI.create(presignEndpoint))
        ...
}
```

3. **S3Service.java:** использовать `presignerForAgents` для generatePresignedPutUrl
```java
// Или проще — подменить hostname в URL после генерации:
public String generatePresignedPutUrl(...) {
    String url = s3Presigner.presignPutObject(presignRequest).url().toString();
    if (presignEndpoint != null && !presignEndpoint.equals(s3Endpoint)) {
        url = url.replace(s3Endpoint, presignEndpoint);
    }
    return url;
}
```

4. **ConfigMap (k8s):**
```yaml
S3_PRESIGN_ENDPOINT: https://services-test.shepaland.ru/screenrecorder/prg-segments
```

### Вариант B: URL rewrite в S3Service (быстрый)

Не менять конфигурацию — просто подменять `minio:9000` на внешний URL:

```java
@Value("${prg.s3.presign-endpoint:}")
private String presignEndpoint;

public String generatePresignedPutUrl(...) {
    String url = s3Presigner.presignPutObject(presignRequest).url().toString();
    if (!presignEndpoint.isBlank()) {
        // Replace internal endpoint with external
        url = url.replace("http://minio:9000/prg-segments", presignEndpoint);
    }
    return url;
}
```

### Рекомендация

**Вариант B** — быстрее в реализации, 5 строк кода + 1 configmap change. Вариант A чище архитектурно.

## Nginx routing (уже настроен)

```nginx
location /prg-segments/ {
    proxy_pass http://minio:9000;
    client_max_body_size 50m;
    proxy_request_buffering off;
}
```

Агент получит URL вида:
```
https://services-test.shepaland.ru/screenrecorder/prg-segments/386597e2-.../00017.mp4?X-Amz-Algorithm=...
```

Nginx проксирует → MinIO получит presigned запрос → upload success → агент вызовет confirm.

## Файлы для изменений

| Файл | Изменение |
|------|-----------|
| `ingest-gateway/src/main/resources/application.yml` | Добавить `presign-endpoint` property |
| `ingest-gateway/src/main/java/com/prg/ingest/service/S3Service.java` | URL rewrite в `generatePresignedPutUrl()` |
| `deploy/k8s/envs/test/configmaps.yaml` или `ingest-gateway-config` | Добавить `S3_PRESIGN_ENDPOINT` |

## Влияние

- **Агент Windows** — заработает без изменений кода (URL станет доступен)
- **Агент macOS** — аналогично
- **Playback** — не затронут (уже проксирует через service)
- **Confirm** — заработает автоматически (upload пройдёт → confirm вызовется)
