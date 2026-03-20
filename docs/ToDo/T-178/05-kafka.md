# T-178.5: Kafka 2 брокера (KRaft)

**Зависит от:** T-178.1
**Блокирует:** T-178.9

---

## Шаги

### 1. Docker compose на каждом сервере

**app1:**
```yaml
# /opt/kafka/docker-compose.yml
services:
  kafka:
    image: apache/kafka:3.7.0
    container_name: kafka
    restart: always
    network_mode: host
    environment:
      KAFKA_NODE_ID: 1
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@app1:9093,2@app2:9093
      KAFKA_LISTENERS: PLAINTEXT://:9092,CONTROLLER://:9093
      KAFKA_INTER_BROKER_LISTENER_NAME: PLAINTEXT
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://app1:9092
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_LOG_DIRS: /var/kafka-logs
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 2
      KAFKA_DEFAULT_REPLICATION_FACTOR: 2
      KAFKA_MIN_INSYNC_REPLICAS: 1
      KAFKA_LOG_RETENTION_HOURS: 168  # 7 дней
      CLUSTER_ID: <generated_cluster_id>
    volumes:
      - /opt/kafka/data:/var/kafka-logs
```

**app2:** то же, но `KAFKA_NODE_ID: 2`, `ADVERTISED_LISTENERS: PLAINTEXT://app2:9092`.

### 2. Создание топиков

```bash
docker exec kafka kafka-topics.sh --bootstrap-server localhost:9092 \
  --create --topic segments.confirmed --partitions 6 --replication-factor 2

docker exec kafka kafka-topics.sh --bootstrap-server localhost:9092 \
  --create --topic commands.issued --partitions 3 --replication-factor 2

docker exec kafka kafka-topics.sh --bootstrap-server localhost:9092 \
  --create --topic device.events --partitions 3 --replication-factor 2

docker exec kafka kafka-topics.sh --bootstrap-server localhost:9092 \
  --create --topic segments.written --partitions 6 --replication-factor 2

docker exec kafka kafka-topics.sh --bootstrap-server localhost:9092 \
  --create --topic webhooks.trigger --partitions 3 --replication-factor 2
```

### 3. Проверка

```bash
docker exec kafka kafka-topics.sh --bootstrap-server localhost:9092 --list
docker exec kafka kafka-metadata.sh --snapshot /var/kafka-logs/__cluster_metadata-0/00000000000000000000.log --cluster-id <id>
```

## Тест-кейсы

| # | Проверка | Ожидание |
|---|----------|----------|
| 1 | `kafka-topics.sh --list` | 5 топиков |
| 2 | `kafka-topics.sh --describe --topic segments.confirmed` | partitions=6, replicas=2, ISR=2 |
| 3 | Produce + consume test message | Сообщение доставлено |
| 4 | Остановить kafka на app2 | Produce/consume продолжает работать (1 broker) |
| 5 | Запустить kafka на app2 | ISR восстановлен до 2 |
