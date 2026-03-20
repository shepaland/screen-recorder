# T-178.4: MinIO distributed на отдельных дисках

**Зависит от:** T-178.1 (диски смонтированы в /mnt/s3-data)
**Блокирует:** T-178.9

---

## Шаги

### 1. Установка MinIO на обоих серверах

```bash
wget https://dl.min.io/server/minio/release/linux-amd64/minio
chmod +x minio && mv minio /usr/local/bin/

# Пользователь:
useradd -r -s /sbin/nologin minio
chown -R minio:minio /mnt/s3-data
```

### 2. Systemd service

```ini
# /etc/systemd/system/minio.service (на обоих серверах)
[Unit]
Description=MinIO S3 Storage
After=network-online.target
Wants=network-online.target

[Service]
User=minio
Group=minio
EnvironmentFile=/etc/default/minio
ExecStart=/usr/local/bin/minio server http://app1/mnt/s3-data http://app2/mnt/s3-data --console-address ":9001"
Restart=always
LimitNOFILE=65536

[Install]
WantedBy=multi-user.target
```

```bash
# /etc/default/minio
MINIO_ROOT_USER=kadero_minio
MINIO_ROOT_PASSWORD=<secure_password>
```

```bash
systemctl daemon-reload
systemctl enable minio
systemctl start minio
```

### 3. Bucket и policy

```bash
# Установить mc (MinIO Client):
wget https://dl.min.io/client/mc/release/linux-amd64/mc
chmod +x mc && mv mc /usr/local/bin/

# Настроить alias:
mc alias set prod http://app1:9000 kadero_minio <password>

# Создать bucket:
mc mb prod/kadero-segments

# Lifecycle (retention 90 дней):
mc ilm rule add prod/kadero-segments --expiry-days 90
```

### 4. Проверка

```bash
# Статус кластера:
mc admin info prod

# Тестовый upload:
echo "test" > /tmp/test.txt
mc cp /tmp/test.txt prod/kadero-segments/test.txt
mc ls prod/kadero-segments/
mc rm prod/kadero-segments/test.txt
```

## Тест-кейсы

| # | Проверка | Ожидание |
|---|----------|----------|
| 1 | `mc admin info prod` | 2 servers, 2 drives, online |
| 2 | Upload файла | Успешно, файл доступен на обоих нодах |
| 3 | Остановить MinIO на app2, upload | Работает (degraded mode) |
| 4 | Запустить MinIO на app2 | Синхронизация, кластер healthy |
| 5 | `mc ilm rule ls prod/kadero-segments` | Expiry: 90 days |
