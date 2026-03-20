# T-178.3: PostgreSQL primary + streaming replica

**Зависит от:** T-178.1
**Блокирует:** T-178.9 (деплой сервисов)

---

## Шаги

### 1. Установка PostgreSQL 16 на обоих серверах
```bash
# На app1 и app2:
sh -c 'echo "deb http://apt.postgresql.org/pub/repos/apt $(lsb_release -cs)-pgdg main" > /etc/apt/sources.list.d/pgdg.list'
wget --quiet -O - https://www.postgresql.org/media/keys/ACCC4CF8.asc | apt-key add -
apt update && apt install -y postgresql-16
```

### 2. Primary (app1)

```bash
# /etc/postgresql/16/main/postgresql.conf
listen_addresses = '*'
port = 5432
max_connections = 200
shared_buffers = 4GB
effective_cache_size = 12GB
work_mem = 32MB
maintenance_work_mem = 512MB
wal_level = replica
max_wal_senders = 5
wal_keep_size = 1GB
synchronous_commit = on
```

```bash
# /etc/postgresql/16/main/pg_hba.conf (добавить):
host replication replicator <app2_ip>/32 scram-sha-256
host all kadero_app <app2_ip>/32 scram-sha-256
host all kadero_app 10.42.0.0/16 scram-sha-256  # k3s pod network
host all kadero_app 10.43.0.0/16 scram-sha-256  # k3s service network
```

```sql
-- Создать пользователя репликации:
CREATE ROLE replicator WITH REPLICATION LOGIN PASSWORD '<repl_password>';

-- Создать БД и пользователя приложения:
CREATE DATABASE kadero_prod;
CREATE USER kadero_app WITH ENCRYPTED PASSWORD '<app_password>';
GRANT ALL ON DATABASE kadero_prod TO kadero_app;
\c kadero_prod
GRANT CREATE ON SCHEMA public TO kadero_app;
```

```bash
systemctl restart postgresql
```

### 3. Replica (app2)

```bash
# Остановить PostgreSQL:
systemctl stop postgresql

# Удалить данные:
rm -rf /var/lib/postgresql/16/main/*

# Забрать базу с primary:
pg_basebackup -h app1 -U replicator -D /var/lib/postgresql/16/main -Fp -Xs -R -P

# Запустить:
systemctl start postgresql
```

Проверить:
```sql
-- На app2:
SELECT pg_is_in_recovery(); -- → true (replica)

-- На app1:
SELECT * FROM pg_stat_replication;
-- → 1 строка (app2 подключён)
```

### 4. Бэкапы

```bash
# Cron на app1:
0 3 * * * pg_dump -Fc kadero_prod > /backup/kadero_prod_$(date +\%Y\%m\%d).dump
0 4 * * * find /backup -name "kadero_prod_*.dump" -mtime +30 -delete
```

## Тест-кейсы

| # | Проверка | Ожидание |
|---|----------|----------|
| 1 | `psql -h app1 -U kadero_app -d kadero_prod -c 'SELECT 1'` | 1 |
| 2 | `psql -h app2 -U kadero_app -d kadero_prod -c 'SELECT pg_is_in_recovery()'` | true |
| 3 | INSERT на app1 → SELECT на app2 | Данные реплицируются < 1s |
| 4 | INSERT на app2 | ERROR: cannot execute in read-only transaction |
| 5 | `pg_dump` бэкап | Файл создаётся, размер > 0 |
