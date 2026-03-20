# T-178.8: Load Balancer — nginx reverse proxy

**Зависит от:** T-178.2 (SSL), T-178.7 (k3s)
**Блокирует:** T-178.11 (smoke-тест)

---

## Шаги

### 1. Установка nginx

```bash
apt install -y nginx
```

### 2. Конфигурация

```nginx
# /etc/nginx/sites-available/kadero.online

# Rate limiting
limit_req_zone $binary_remote_addr zone=general:10m rate=100r/s;

upstream kadero_backend {
    server app1:443 max_fails=3 fail_timeout=30s;
    server app2:443 max_fails=3 fail_timeout=30s;
}

# HTTP → HTTPS redirect
server {
    listen 80;
    server_name kadero.online www.kadero.online;
    return 301 https://kadero.online$request_uri;
}

# www → non-www redirect
server {
    listen 443 ssl http2;
    server_name www.kadero.online;

    ssl_certificate     /etc/letsencrypt/live/kadero.online/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/kadero.online/privkey.pem;

    return 301 https://kadero.online$request_uri;
}

# Main server
server {
    listen 443 ssl http2;
    server_name kadero.online;

    ssl_certificate     /etc/letsencrypt/live/kadero.online/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/kadero.online/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers HIGH:!aNULL:!MD5;
    ssl_prefer_server_ciphers on;
    ssl_session_cache shared:SSL:10m;

    # Security headers
    add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
    add_header X-Frame-Options DENY always;
    add_header X-Content-Type-Options nosniff always;

    # Limits
    client_max_body_size 100m;
    proxy_read_timeout 120s;
    proxy_connect_timeout 10s;
    proxy_send_timeout 60s;

    # Rate limiting
    limit_req zone=general burst=50 nodelay;

    location / {
        proxy_pass https://kadero_backend;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
    }

    # Health check (не проксировать)
    location /healthz {
        return 200 'OK';
        add_header Content-Type text/plain;
    }
}
```

### 3. Активация

```bash
ln -sf /etc/nginx/sites-available/kadero.online /etc/nginx/sites-enabled/
rm -f /etc/nginx/sites-enabled/default
nginx -t && systemctl reload nginx
```

### 4. Проверка failover

```bash
# Остановить app1:
ssh app1 'sudo k3s kubectl cordon app1'
# Трафик должен идти на app2
curl -s https://kadero.online/api/v1/health
# → 200 OK

# Вернуть app1:
ssh app1 'sudo k3s kubectl uncordon app1'
```

## Тест-кейсы

| # | Проверка | Ожидание |
|---|----------|----------|
| 1 | `curl -I http://kadero.online` | 301 → https |
| 2 | `curl -I https://kadero.online` | 200, HSTS header |
| 3 | `curl https://kadero.online/healthz` | 200 OK |
| 4 | Остановить app1 → `curl https://kadero.online/healthz` | 200 OK (через app2) |
| 5 | SSL Labs test | Grade A или A+ |
| 6 | 200 concurrent requests | Все 200 OK, rate limit не срабатывает |
