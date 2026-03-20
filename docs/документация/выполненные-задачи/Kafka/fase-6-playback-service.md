# Фаза 6: playback-service (HLS)

> **Влияние на текущую систему: НОЛЬ**
> Новый сервис. Не зависит от Kafka — читает из PostgreSQL + MinIO. Тестируется через port-forward.

---

## Цель

Создать playback-service для HLS-воспроизведения записей. M3U8 playlist из PostgreSQL, видеосегменты из MinIO. Авторизация через auth-service.

## Предусловия

- PostgreSQL содержит confirmed сегменты
- MinIO содержит видеофайлы
- **Не зависит от Kafka** — можно делать параллельно с Фазами 3-5

---

## Задачи

### KFK-050: Инициализация playback-service

**Что сделать:**

```
playback-service/
├── pom.xml
├── src/main/java/com/prg/playback/
│   ├── PlaybackServiceApplication.java
│   ├── config/
│   │   ├── S3Config.java
│   │   ├── SecurityConfig.java
│   │   └── JwtValidationFilter.java
│   ├── controller/
│   │   └── PlaybackController.java
│   ├── service/
│   │   └── PlaybackService.java
│   ├── entity/
│   │   ├── Segment.java       (read-only copy)
│   │   └── RecordingSession.java (read-only copy)
│   ├── repository/
│   │   ├── SegmentRepository.java
│   │   └── RecordingSessionRepository.java
│   └── dto/
│       └── SessionPlaybackInfo.java
└── src/main/resources/
    └── application.yml
```

**pom.xml:**
```xml
<parent>spring-boot-starter-parent 3.3.0</parent>
<dependencies>
    spring-boot-starter-web
    spring-boot-starter-data-jpa
    postgresql
    software.amazon.awssdk:s3 (2.25.0)
    spring-boot-starter-actuator
    spring-boot-starter-validation
    springdoc-openapi-starter-webmvc-ui (2.3.0)
    lombok
</dependencies>
```

**application.yml:**
```yaml
server:
  port: 8082

spring:
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:prg_dev}
    username: ${DB_USER:prg_app}
    password: ${DB_PASSWORD:changeme}
    hikari:
      maximum-pool-size: 10     # read-only, меньше чем ingest
      minimum-idle: 3
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
  jackson:
    property-naming-strategy: SNAKE_CASE

prg:
  s3:
    endpoint: ${S3_ENDPOINT:http://localhost:9000}
    bucket: ${S3_BUCKET:prg-segments}
    access-key: ${S3_ACCESS_KEY:minioadmin}
    secret-key: ${S3_SECRET_KEY:minioadmin}
    presign-expiry-sec: ${S3_PRESIGN_EXPIRY:300}
  auth-service:
    base-url: ${AUTH_SERVICE_URL:http://localhost:8081}
    internal-api-key: ${INTERNAL_API_KEY:}
```

**Критерий приёмки:** `./mvnw compile` проходит. Сервис стартует.

---

### KFK-051: M3U8 Playlist Generator

**Что сделать:**

```java
@RestController
@RequestMapping("/api/v1/playback")
@RequiredArgsConstructor
public class PlaybackController {

    private final PlaybackService playbackService;

    @GetMapping(value = "/sessions/{sessionId}/playlist.m3u8",
                produces = "application/vnd.apple.mpegurl")
    public ResponseEntity<String> getPlaylist(
            @PathVariable UUID sessionId,
            HttpServletRequest request) {

        DevicePrincipal principal = getPrincipal(request);
        String playlist = playbackService.generatePlaylist(sessionId, principal.getTenantId());
        return ResponseEntity.ok()
            .header("Cache-Control", "no-cache")
            .body(playlist);
    }
}
```

```java
@Service
@RequiredArgsConstructor
public class PlaybackService {

    private final SegmentRepository segmentRepository;
    private final RecordingSessionRepository sessionRepository;
    private final S3Service s3Service;

    public String generatePlaylist(UUID sessionId, UUID tenantId) {
        RecordingSession session = sessionRepository
            .findByIdAndTenantId(sessionId, tenantId)
            .orElseThrow(() -> new NotFoundException("Session not found"));

        List<Segment> segments = segmentRepository
            .findBySessionIdAndTenantIdOrderBySequenceNum(sessionId, tenantId);

        if (segments.isEmpty()) {
            throw new NotFoundException("No segments for session");
        }

        StringBuilder m3u8 = new StringBuilder();
        m3u8.append("#EXTM3U\n");
        m3u8.append("#EXT-X-VERSION:3\n");
        m3u8.append("#EXT-X-TARGETDURATION:")
            .append(maxDurationSec(segments)).append("\n");
        m3u8.append("#EXT-X-MEDIA-SEQUENCE:0\n");

        for (Segment seg : segments) {
            double durationSec = seg.getDurationMs() != null
                ? seg.getDurationMs() / 1000.0 : 60.0;
            m3u8.append("#EXTINF:").append(String.format("%.3f", durationSec)).append(",\n");
            // URL к segment proxy endpoint
            m3u8.append("segments/").append(seg.getId()).append("\n");
        }

        // Если session ended — VOD playlist
        if (session.getEndedTs() != null) {
            m3u8.append("#EXT-X-ENDLIST\n");
        }

        return m3u8.toString();
    }

    private int maxDurationSec(List<Segment> segments) {
        return segments.stream()
            .mapToInt(s -> s.getDurationMs() != null ? (s.getDurationMs() / 1000 + 1) : 61)
            .max().orElse(61);
    }
}
```

**Критерий приёмки:** `GET /api/v1/playback/sessions/{id}/playlist.m3u8` → валидный HLS playlist с `#EXTINF` для каждого сегмента.

---

### KFK-052: Segment Proxy / Redirect

**Что сделать:**

```java
@GetMapping("/segments/{segmentId}")
public ResponseEntity<Void> getSegment(
        @PathVariable UUID segmentId,
        HttpServletRequest request) {

    DevicePrincipal principal = getPrincipal(request);

    Segment segment = segmentRepository.findById(segmentId)
        .orElseThrow(() -> new NotFoundException("Segment not found"));

    // Tenant isolation
    if (!segment.getTenantId().equals(principal.getTenantId())) {
        throw new ForbiddenException("Access denied");
    }

    // Presigned GET URL от MinIO (5 минут)
    String presignedUrl = s3Service.generatePresignedGetUrl(
        segment.getS3Key(), Duration.ofMinutes(5));

    return ResponseEntity.status(HttpStatus.FOUND)  // 302 Redirect
        .header("Location", presignedUrl)
        .header("Cache-Control", "private, max-age=300")
        .build();
}
```

**Альтернатива:** вместо redirect — stream-proxy (ingest-gw уже проксирует через MinIO). Redirect быстрее и снижает нагрузку на playback-service.

**S3Service.generatePresignedGetUrl():**
```java
public String generatePresignedGetUrl(String key, Duration expiry) {
    var request = GetObjectPresignRequest.builder()
        .signatureDuration(expiry)
        .getObjectRequest(g -> g.bucket(bucket).key(key))
        .build();
    return s3Presigner.presignGetObject(request).url().toString();
}
```

**Критерий приёмки:** `GET /segments/{id}` → 302 Redirect на MinIO presigned URL. Видеофайл скачивается.

---

### KFK-053: Авторизация playback-service

**Что сделать:**

Скопировать JWT validation filter из ingest-gateway (или извлечь в shared library):
- `JwtValidationFilter` → извлекает JWT, вызывает auth-service
- `DevicePrincipal` / `UserPrincipal` → tenant_id, user_id, permissions
- Tenant isolation: session.tenant_id = principal.tenant_id

**Permissions:**
- Для начала: любой аутентифицированный пользователь в tenant может смотреть записи
- В будущем: `RECORDINGS:VIEW` permission для granular control

**Критерий приёмки:** Без JWT → 401. JWT tenant A → видит только записи tenant A.

---

### KFK-054: K8s манифесты + Dockerfile

**Что сделать:**

1. `deploy/docker/playback-service/Dockerfile`:
   ```dockerfile
   FROM maven:3.9-eclipse-temurin-21 AS build
   WORKDIR /app
   COPY pom.xml .
   RUN mvn dependency:go-offline -B
   COPY src ./src
   RUN mvn package -DskipTests -B

   FROM eclipse-temurin:21-jre-alpine
   RUN addgroup -g 1001 appgroup && adduser -u 1001 -G appgroup -D appuser
   WORKDIR /app
   COPY --from=build /app/target/*.jar app.jar
   USER 1001
   EXPOSE 8082
   ENTRYPOINT ["java", "-jar", "app.jar"]
   ```

2. `deploy/k8s/playback-service/`:
   - `deployment.yaml` (replicas: 1, resources: 0.25 CPU / 512Mi)
   - `service.yaml` (ClusterIP :8082)
   - `configmap.yaml` (DB, S3, auth-service URLs)

3. Network policy: egress к PostgreSQL (172.17.0.1:5432), MinIO (minio:9000), auth-service (auth-service:8081)

**Критерий приёмки:** Pod Running, health check OK.

---

### KFK-055: Верификация на test

**Что сделать:**

```bash
# Port-forward
sudo k3s kubectl -n test-screen-record port-forward svc/playback-service 8082:8082 &

# Получить JWT
TOKEN=$(curl -s -X POST "https://services-test.shepaland.ru/screenrecorder/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"maksim","password":"#6TY0N0d"}' | jq -r '.access_token')

# Получить session_id (из PostgreSQL или Dashboard)
SESSION_ID="..."

# Playlist
curl -s "http://localhost:8082/api/v1/playback/sessions/$SESSION_ID/playlist.m3u8" \
  -H "Authorization: Bearer $TOKEN"
# Ожидаем: #EXTM3U ... #EXTINF:60.000, ... segments/uuid ...

# Воспроизведение в VLC
vlc "http://localhost:8082/api/v1/playback/sessions/$SESSION_ID/playlist.m3u8"

# Или в ffplay
ffplay "http://localhost:8082/api/v1/playback/sessions/$SESSION_ID/playlist.m3u8"
```

**Nginx route НЕ добавляется.**

**Критерий приёмки:** M3U8 валидный. VLC/ffplay воспроизводит запись.

---

## Чеклист завершения фазы

- [ ] playback-service pod Running
- [ ] `GET /sessions/{id}/playlist.m3u8` → валидный HLS playlist
- [ ] `GET /segments/{id}` → 302 Redirect на MinIO presigned URL
- [ ] VLC/ffplay воспроизводит запись через port-forward
- [ ] Tenant isolation: JWT tenant A → только записи tenant A
- [ ] **Nginx routes НЕ добавлены**
- [ ] **Текущая система не затронута**
