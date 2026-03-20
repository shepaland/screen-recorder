# T-178.6: OpenSearch 2 ноды

**Зависит от:** T-178.1
**Блокирует:** T-178.9

---

## Шаги

### 1. Docker compose на каждом сервере

**app1:**
```yaml
# /opt/opensearch/docker-compose.yml
services:
  opensearch:
    image: opensearchproject/opensearch:2.12.0
    container_name: opensearch
    restart: always
    environment:
      - cluster.name=kadero-prod
      - node.name=os-node-1
      - discovery.seed_hosts=app1,app2
      - cluster.initial_cluster_manager_nodes=os-node-1,os-node-2
      - bootstrap.memory_lock=true
      - OPENSEARCH_JAVA_OPTS=-Xms1g -Xmx1g
      - plugins.security.disabled=true  # внутренняя сеть, без TLS
    ulimits:
      memlock: { soft: -1, hard: -1 }
      nofile: { soft: 65536, hard: 65536 }
    volumes:
      - /opt/opensearch/data:/usr/share/opensearch/data
    ports:
      - "9200:9200"
      - "9300:9300"
```

**app2:** `node.name=os-node-2`, остальное идентично.

### 2. Настройки sysctl

```bash
# На обоих серверах:
echo 'vm.max_map_count=262144' >> /etc/sysctl.conf
sysctl -p
```

### 3. Index templates

```bash
# Шаблон для сегментов (месячные индексы):
curl -X PUT "http://app1:9200/_index_template/segments-template" \
  -H 'Content-Type: application/json' -d '{
  "index_patterns": ["segments-*"],
  "template": {
    "settings": {
      "number_of_shards": 2,
      "number_of_replicas": 1,
      "refresh_interval": "30s"
    }
  }
}'
```

## Тест-кейсы

| # | Проверка | Ожидание |
|---|----------|----------|
| 1 | `curl http://app1:9200/_cluster/health?pretty` | status: green, nodes: 2 |
| 2 | `curl http://app1:9200/_cat/nodes?v` | 2 ноды, обе data + master |
| 3 | Создать тестовый индекс с replica=1 | Status: green |
| 4 | Остановить OpenSearch на app2 | Кластер yellow, данные доступны |
| 5 | Запустить OpenSearch на app2 | Кластер green |
