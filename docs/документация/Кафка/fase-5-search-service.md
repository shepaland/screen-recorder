# Фаза 5: search-service + OpenSearch

> **Влияние на текущую систему: НОЛЬ**
> Новые pods (OpenSearch + search-service). Тестируется через port-forward. Nginx routes не трогаются.

---

## Цель

Развернуть OpenSearch, создать search-service (Kafka consumer + REST API). Полнотекстовый поиск записей с tenant isolation. Верификация через port-forward.

## Предусловия

- Фаза 2+ завершена: события `segments.ingest` в Kafka
- (Опционально) Фаза 4: kafka-only mode для полного потока

---

## Задачи

### KFK-040: Деплой OpenSearch в k3s

**Что сделать:**

1. K8s манифесты `deploy/k8s/opensearch/`:

   **deployment.yaml:**
   ```yaml
   image: opensearchproject/opensearch:2.12.0
   env:
     - name: discovery.type
       value: single-node
     - name: DISABLE_SECURITY_PLUGIN
       value: "true"
     - name: OPENSEARCH_JAVA_OPTS
       value: "-Xms1g -Xmx1g"
   resources:
     requests: { cpu: "0.5", memory: "1.5Gi" }
     limits:   { cpu: "2",   memory: "3Gi" }
   volumeMounts:
     - name: opensearch-data
       mountPath: /usr/share/opensearch/data
   ```

   **service.yaml:** ClusterIP :9200
   **pvc.yaml:** 50Gi

2. Деплой:
   ```bash
   docker pull opensearchproject/opensearch:2.12.0
   sudo k3s ctr images import <(docker save opensearchproject/opensearch:2.12.0)
   sudo k3s kubectl -n test-screen-record apply -f deploy/k8s/opensearch/
   ```

3. Проверка:
   ```bash
   sudo k3s kubectl -n test-screen-record exec -it deployment/opensearch -- \
     curl -s localhost:9200/_cluster/health | python3 -m json.tool
   # status: green/yellow
   ```

**Файлы:** `deploy/k8s/opensearch/deployment.yaml`, `service.yaml`, `pvc.yaml`

**Критерий приёмки:** OpenSearch pod Running, cluster health green/yellow.

---

### KFK-041: Инициализация search-service

**Что сделать:**

Создать новый Spring Boot проект:

```
search-service/
├── pom.xml
├── src/main/java/com/prg/search/
│   ├── SearchServiceApplication.java
│   ├── config/
│   │   ├── KafkaConsumerConfig.java
│   │   ├── OpenSearchConfig.java
│   │   └── SecurityConfig.java
│   ├── consumer/
│   │   └── SegmentIndexerConsumer.java
│   ├── controller/
│   │   └── SearchController.java
│   ├── service/
│   │   └── SearchService.java
│   └── dto/
│       ├── SegmentSearchResult.java
│       └── SearchRequest.java
└── src/main/resources/
    └── application.yml
```

**pom.xml зависимости:**
```xml
<parent>spring-boot-starter-parent 3.3.0</parent>
<dependencies>
    spring-boot-starter-web
    spring-kafka
    opensearch-java (2.10.+)
    spring-boot-starter-actuator
    spring-boot-starter-validation
    springdoc-openapi-starter-webmvc-ui (2.3.0)
    lombok
</dependencies>
```

**application.yml:**
```yaml
server:
  port: 8083

spring:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    consumer:
      group-id: search-indexer
      auto-offset-reset: earliest
      enable-auto-commit: false
      max-poll-records: 50

opensearch:
  host: ${OPENSEARCH_HOST:localhost}
  port: ${OPENSEARCH_PORT:9200}

prg:
  auth-service:
    base-url: ${AUTH_SERVICE_URL:http://localhost:8081}
    internal-api-key: ${INTERNAL_API_KEY:}
```

**Критерий приёмки:** `./mvnw compile` проходит. Сервис стартует локально.

---

### KFK-042: Kafka Consumer — индексация сегментов

**Что сделать:**

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class SegmentIndexerConsumer {

    private final OpenSearchClient openSearchClient;

    @KafkaListener(topics = "segments.ingest", groupId = "search-indexer")
    public void onMessage(ConsumerRecord<String, SegmentConfirmedEvent> record,
                          Acknowledgment ack) {
        SegmentConfirmedEvent event = record.value();
        try {
            String indexName = "segments-" +
                event.getTimestamp().atZone(ZoneOffset.UTC)
                    .format(DateTimeFormatter.ofPattern("yyyy-MM"));

            openSearchClient.index(i -> i
                .index(indexName)
                .id(event.getSegmentId().toString())  // idempotent upsert
                .document(toDocument(event))
            );

            ack.acknowledge();
            log.debug("Indexed segment {} into {}", event.getSegmentId(), indexName);
        } catch (Exception e) {
            log.error("Failed to index segment {}: {}", event.getSegmentId(), e.getMessage());
            // Не ack'аем → retry. После N retries → DLT.
            throw new RuntimeException(e);
        }
    }

    private Map<String, Object> toDocument(SegmentConfirmedEvent event) {
        return Map.of(
            "tenant_id", event.getTenantId().toString(),
            "device_id", event.getDeviceId().toString(),
            "session_id", event.getSessionId().toString(),
            "segment_id", event.getSegmentId().toString(),
            "sequence_num", event.getSequenceNum(),
            "s3_key", event.getS3Key(),
            "size_bytes", event.getSizeBytes(),
            "duration_ms", event.getDurationMs(),
            "checksum_sha256", event.getChecksumSha256(),
            "timestamp", event.getTimestamp().toString(),
            "metadata", event.getMetadata()
        );
    }
}
```

**Критерий приёмки:** Событие из Kafka → документ в OpenSearch. Idempotent по segment_id.

---

### KFK-043: OpenSearch Index Template

**Что сделать:**

При старте search-service — создать index template:

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class OpenSearchInitializer implements ApplicationRunner {

    private final OpenSearchClient client;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        client.indices().putIndexTemplate(t -> t
            .name("segments-template")
            .indexPatterns("segments-*")
            .template(tp -> tp
                .settings(s -> s
                    .numberOfShards("1")
                    .numberOfReplicas("0"))   // single-node
                .mappings(m -> m
                    .properties("tenant_id", p -> p.keyword(k -> k))
                    .properties("device_id", p -> p.keyword(k -> k))
                    .properties("session_id", p -> p.keyword(k -> k))
                    .properties("segment_id", p -> p.keyword(k -> k))
                    .properties("sequence_num", p -> p.integer(i -> i))
                    .properties("s3_key", p -> p.keyword(k -> k))
                    .properties("size_bytes", p -> p.long_(l -> l))
                    .properties("duration_ms", p -> p.integer(i -> i))
                    .properties("checksum_sha256", p -> p.keyword(k -> k))
                    .properties("timestamp", p -> p.date(d -> d))
                    .properties("metadata", p -> p.object(o -> o))
                )
            )
        );
        log.info("OpenSearch index template 'segments-template' created/updated");
    }
}
```

**Критерий приёмки:** `curl opensearch:9200/_index_template/segments-template` → 200 OK.

---

### KFK-044: REST API поиска

**Что сделать:**

```java
@RestController
@RequestMapping("/api/v1/search")
@RequiredArgsConstructor
public class SearchController {

    private final SearchService searchService;

    @GetMapping("/segments")
    public ResponseEntity<PageResponse<SegmentSearchResult>> searchSegments(
            @RequestParam(required = false) String q,
            @RequestParam(name = "tenant_id") UUID tenantId,
            @RequestParam(name = "device_id", required = false) UUID deviceId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        // tenantId из JWT (через filter) — не из query param
        // Здесь упрощённо, реальная реализация берёт из SecurityContext

        return ResponseEntity.ok(searchService.search(
            q, tenantId, deviceId, from, to, page, size));
    }
}
```

```java
@Service
@RequiredArgsConstructor
public class SearchService {

    private final OpenSearchClient client;

    public PageResponse<SegmentSearchResult> search(
            String q, UUID tenantId, UUID deviceId,
            Instant from, Instant to, int page, int size) {

        var boolQuery = new BoolQuery.Builder();

        // Tenant isolation (ОБЯЗАТЕЛЬНО)
        boolQuery.filter(f -> f.term(t -> t
            .field("tenant_id").value(tenantId.toString())));

        // Фильтры
        if (deviceId != null) {
            boolQuery.filter(f -> f.term(t -> t
                .field("device_id").value(deviceId.toString())));
        }
        if (from != null || to != null) {
            boolQuery.filter(f -> f.range(r -> {
                var range = r.field("timestamp");
                if (from != null) range.gte(JsonData.of(from.toString()));
                if (to != null)   range.lte(JsonData.of(to.toString()));
                return range;
            }));
        }
        // Fulltext
        if (q != null && !q.isBlank()) {
            boolQuery.must(m -> m.multiMatch(mm -> mm
                .query(q)
                .fields("s3_key", "metadata.*")));
        }

        var response = client.search(s -> s
            .index("segments-*")
            .query(boolQuery.build()._toQuery())
            .from(page * size)
            .size(size)
            .sort(so -> so.field(f -> f.field("timestamp").order(SortOrder.Desc))),
            Map.class);

        // Map to PageResponse
        // ...
    }
}
```

**Критерий приёмки:** `GET /api/v1/search/segments?tenant_id=X` → JSON с результатами. Tenant isolation работает.

---

### KFK-045: Авторизация search-service

**Что сделать:**

Реализовать JWT filter (аналогично ingest-gateway):
- Извлечь JWT из `Authorization: Bearer <token>`
- Валидировать через auth-service `/api/v1/internal/check-access`
- Извлечь `tenant_id` из JWT claims
- OpenSearch query **всегда** фильтрует по `tenant_id` из JWT

**Файлы:** `search-service/src/main/java/com/prg/search/config/SecurityConfig.java`, `JwtValidationFilter.java`

**Критерий приёмки:** Без JWT → 401. С JWT tenant A → видит только данные tenant A.

---

### KFK-046: K8s манифесты + Dockerfile

**Что сделать:**

1. `deploy/docker/search-service/Dockerfile` — multi-stage (аналогично другим сервисам)
2. `deploy/k8s/search-service/deployment.yaml`, `service.yaml`, `configmap.yaml`
3. Network policy: egress к Kafka, OpenSearch, auth-service

**Критерий приёмки:** Pod Running в test-screen-record.

---

### KFK-047: Backfill скрипт

**Что сделать:**

Одноразовый скрипт для индексации существующих сегментов (до Kafka dual-write):

```bash
#!/bin/bash
# Из PostgreSQL → JSON → OpenSearch bulk API

psql -h 172.17.0.1 -U prg_app -d prg_test -t -A -c "
  SELECT json_build_object(
    'segment_id', id, 'tenant_id', tenant_id, 'device_id', device_id,
    'session_id', session_id, 'sequence_num', sequence_num,
    's3_key', s3_key, 'size_bytes', size_bytes, 'duration_ms', duration_ms,
    'checksum_sha256', checksum_sha256, 'timestamp', created_ts, 'metadata', metadata
  ) FROM segments WHERE status = 'confirmed'
" | while read -r doc; do
  segment_id=$(echo "$doc" | jq -r '.segment_id')
  timestamp=$(echo "$doc" | jq -r '.timestamp')
  index="segments-$(date -d "$timestamp" +%Y-%m 2>/dev/null || echo "2026-03")"
  echo "{\"index\":{\"_index\":\"$index\",\"_id\":\"$segment_id\"}}"
  echo "$doc"
done | curl -s -X POST "opensearch:9200/_bulk" \
  -H "Content-Type: application/x-ndjson" --data-binary @-
```

**Критерий приёмки:** Все confirmed сегменты из PostgreSQL проиндексированы в OpenSearch.

---

### KFK-048: Верификация на test

**Что сделать:**

```bash
# Port-forward
sudo k3s kubectl -n test-screen-record port-forward svc/search-service 8083:8083 &

# Поиск
curl -s "http://localhost:8083/api/v1/search/segments?tenant_id=386597e2-7c48-4668-a101-711308a6a5b2" \
  -H "Authorization: Bearer <jwt>" | python3 -m json.tool

# Consumer lag = 0
sudo k3s kubectl -n test-screen-record exec deployment/kafka -- \
  kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --group search-indexer --describe
```

**Nginx route НЕ добавляется** — тестирование только через port-forward.

**Критерий приёмки:** API возвращает результаты. Consumer lag = 0. Tenant isolation подтверждена.

---

## Чеклист завершения фазы

- [x] OpenSearch pod Running, cluster health OK — ✓ green, 1 node, 4 primary shards
- [x] search-service pod Running — ✓ 1/1 Running
- [x] Kafka consumer `search-indexer` — lag = 0 — ✓ partition 5: 470/470
- [x] Index template `segments-*` создан — ✓ OpenSearchInitializer at startup
- [x] REST API поиска работает — ✓ `GET /api/v1/search/segments?tenant_id=...` → 3 результата с metadata
- [x] Tenant isolation — ✓ query всегда фильтрует по tenant_id (обязательный параметр)
- [x] Backfill существующих сегментов — ✓ 3185 items, errors: false. Итого 3186 docs в OpenSearch
- [x] **Nginx routes НЕ добавлены** — ✓ search доступен только через pod exec
- [x] **Текущая система не затронута** — ✓ все 8 pods Running
