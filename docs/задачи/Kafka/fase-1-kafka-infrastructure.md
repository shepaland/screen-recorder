# Фаза 1: Kafka — Инфраструктура

> **Влияние на текущую систему: НОЛЬ**
> Kafka запускается как изолированный pod. Ни один существующий сервис к ней не подключается.

---

## Цель

Развернуть Apache Kafka в KRaft mode (без ZooKeeper) в k3s кластере на test-стейджинге. Создать topics. Проверить connectivity.

## Предусловия

- k3s кластер на shepaland-cloud работает
- Namespace `test-screen-record` существует
- Свободно: ~1 vCPU, 2 GB RAM, 20 GB disk

---

## Задачи

### KFK-001: K8s манифесты Kafka

**Что сделать:**

Создать директорию `deploy/k8s/kafka/` с файлами:

1. **`configmap.yaml`** — `server.properties` для KRaft mode:
   ```properties
   process.roles=broker,controller
   node.id=1
   controller.quorum.voters=1@kafka:9093
   listeners=PLAINTEXT://:9092,CONTROLLER://:9093
   inter.broker.listener.name=PLAINTEXT
   controller.listener.names=CONTROLLER
   log.dirs=/var/kafka-logs
   num.partitions=3
   default.replication.factor=1
   min.insync.replicas=1
   offsets.topic.replication.factor=1
   transaction.state.log.replication.factor=1
   transaction.state.log.min.isr=1
   log.retention.hours=168
   log.segment.bytes=1073741824
   ```

2. **`pvc.yaml`** — PersistentVolumeClaim:
   - Name: `kafka-data`
   - Size: `20Gi`
   - AccessMode: `ReadWriteOnce`
   - StorageClass: `local-path` (k3s default)

3. **`deployment.yaml`** — Deployment:
   - Image: `apache/kafka:3.7.0`
   - Replicas: 1
   - Resources: requests 0.5 CPU / 1Gi RAM, limits 1 CPU / 2Gi RAM
   - Volume: `kafka-data` mounted at `/var/kafka-logs`
   - Env: `KAFKA_KRAFT_CLUSTER_ID` (сгенерировать UUID)
   - initContainer: format storage (`kafka-storage.sh format`)
   - Liveness/readiness: TCP probe на :9092

4. **`service.yaml`** — Service:
   - Name: `kafka`
   - Type: ClusterIP
   - Port: 9092 → 9092

**Файлы:** `deploy/k8s/kafka/configmap.yaml`, `pvc.yaml`, `deployment.yaml`, `service.yaml`

**Критерий приёмки:** Манифесты проходят `kubectl apply --dry-run=server`

---

### KFK-002: Деплой Kafka в k3s (test)

**Что сделать:**

```bash
# На shepaland-cloud
docker pull apache/kafka:3.7.0
sudo k3s ctr images import <(docker save apache/kafka:3.7.0)
sudo k3s kubectl -n test-screen-record apply -f deploy/k8s/kafka/
sudo k3s kubectl -n test-screen-record get pods -l app=kafka
# Дождаться Running
sudo k3s kubectl -n test-screen-record logs deployment/kafka
# Проверить: no errors, KRaft initialized, listeners ready
```

**Критерий приёмки:** Pod в статусе `Running`, логи чистые, порт 9092 слушает.

---

### KFK-003: Создание topics

**Что сделать:**

Создать Kubernetes Job `kafka-init-topics` или выполнить вручную:

```bash
# Из pod Kafka
sudo k3s kubectl -n test-screen-record exec -it deployment/kafka -- bash

# Создать topics
kafka-topics.sh --bootstrap-server localhost:9092 --create --topic segments.ingest \
  --partitions 6 --replication-factor 1 \
  --config retention.ms=604800000        # 7 дней

kafka-topics.sh --bootstrap-server localhost:9092 --create --topic commands.issued \
  --partitions 6 --replication-factor 1 \
  --config retention.ms=259200000        # 3 дня

kafka-topics.sh --bootstrap-server localhost:9092 --create --topic device.events \
  --partitions 3 --replication-factor 1 \
  --config retention.ms=604800000        # 7 дней

kafka-topics.sh --bootstrap-server localhost:9092 --create --topic audit.events \
  --partitions 3 --replication-factor 1 \
  --config retention.ms=31536000000      # 365 дней

kafka-topics.sh --bootstrap-server localhost:9092 --create --topic webhooks.outbound \
  --partitions 3 --replication-factor 1 \
  --config retention.ms=259200000        # 3 дня

# Проверить
kafka-topics.sh --bootstrap-server localhost:9092 --list
kafka-topics.sh --bootstrap-server localhost:9092 --describe --topic segments.ingest
```

**Опционально:** оформить как Init Job (`deploy/k8s/kafka/init-topics-job.yaml`) для воспроизводимости.

**Критерий приёмки:** 5 topics созданы, `--describe` показывает правильные partitions и retention.

---

### KFK-004: Network Policy

**Что сделать:**

Обновить `deploy/k8s/network-policies.yaml`:

1. Заменить ссылки на `nats:4222` → `kafka:9092` (lines 239-246, 357-364)
2. Добавить NetworkPolicy для Kafka pod:
   ```yaml
   # Kafka: ingress от сервисов, egress к controller
   apiVersion: networking.k8s.io/v1
   kind: NetworkPolicy
   metadata:
     name: kafka-network-policy
   spec:
     podSelector:
       matchLabels:
         app: kafka
     policyTypes:
       - Ingress
     ingress:
       - from:
           - podSelector:
               matchLabels:
                 app: ingest-gateway
           - podSelector:
               matchLabels:
                 app: control-plane
           - podSelector:
               matchLabels:
                 app: search-service
           - podSelector:
               matchLabels:
                 app: segment-writer
         ports:
           - protocol: TCP
             port: 9092
   ```

**Файлы:** `deploy/k8s/network-policies.yaml`

**Критерий приёмки:** `kubectl apply`, сервисы могут подключиться к kafka:9092, внешний трафик заблокирован.

---

### KFK-005: ConfigMap — KAFKA_BOOTSTRAP_SERVERS

**Что сделать:**

Добавить env variable в ConfigMap'ы сервисов:

```yaml
# deploy/k8s/envs/test/configmaps.yaml — добавить в секции ingest-gateway и control-plane:
KAFKA_BOOTSTRAP_SERVERS: "kafka:9092"
KAFKA_DUAL_WRITE: "false"           # НЕ активировать пока
KAFKA_CONFIRM_MODE: "sync"          # sync = текущее поведение
```

**Не требует передеплоя** — переменные не используются, пока нет Spring Kafka зависимости.

**Файлы:** `deploy/k8s/envs/test/configmaps.yaml`, `deploy/k8s/envs/prod/configmaps.yaml`

**Критерий приёмки:** `kubectl get configmap` показывает новые переменные.

---

### KFK-006: Smoke-тест

**Что сделать:**

```bash
# Producer test (из pod Kafka)
sudo k3s kubectl -n test-screen-record exec -it deployment/kafka -- \
  kafka-console-producer.sh --bootstrap-server localhost:9092 --topic segments.ingest
# Набрать: {"test": "hello"} + Enter + Ctrl+D

# Consumer test (из другого терминала)
sudo k3s kubectl -n test-screen-record exec -it deployment/kafka -- \
  kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic segments.ingest --from-beginning
# Должен вывести: {"test": "hello"}

# Consumer groups
sudo k3s kubectl -n test-screen-record exec -it deployment/kafka -- \
  kafka-consumer-groups.sh --bootstrap-server localhost:9092 --list

# Connectivity из другого pod (ingest-gateway)
sudo k3s kubectl -n test-screen-record exec -it deployment/ingest-gateway -- \
  sh -c "cat < /dev/tcp/kafka/9092 && echo OK || echo FAIL"
```

**Критерий приёмки:** Publish/subscribe работает, connectivity из сервисных pods подтверждена.

---

### KFK-007: Мониторинг (опционально)

**Что сделать:**

1. Включить JMX exporter в Kafka deployment:
   ```yaml
   env:
     - name: KAFKA_JMX_PORT
       value: "9999"
     - name: KAFKA_JMX_HOSTNAME
       value: "localhost"
   ```

2. Добавить JMX exporter sidecar (jmx-prometheus-javaagent) или standalone Prometheus target.

3. Grafana dashboard: broker throughput, partition lag, request rate.

**Приоритет:** Low. Можно отложить до Фазы 3-4, когда consumer'ы начнут работать и lag станет значимым.

---

## Чеклист завершения фазы

- [ ] Kafka pod Running в `test-screen-record`
- [ ] 5 topics созданы с правильным retention
- [ ] Publish/subscribe smoke-тест пройден
- [ ] Network policy: сервисы видят kafka:9092
- [ ] ConfigMap'ы обновлены (KAFKA_BOOTSTRAP_SERVERS)
- [ ] **Текущая система не затронута** — heartbeat, presign, confirm работают как раньше
