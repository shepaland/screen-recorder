# T-178.2: DNS + SSL (kadero.online, Let's Encrypt)

**Зависит от:** T-178.1
**Блокирует:** T-178.8 (Load Balancer)

---

## Шаги

### 1. DNS записи
- [ ] У регистратора домена kadero.online:
```
kadero.online       A     <lb_public_ip>
www.kadero.online   CNAME kadero.online
```
- [ ] Проверить: `dig kadero.online` → IP LB
- [ ] TTL: 300 (5 минут, для быстрого переключения)

### 2. SSL сертификат (Let's Encrypt)

На LB:
```bash
apt install -y certbot

# HTTP-01 challenge (нужен открытый порт 80)
certbot certonly --standalone -d kadero.online -d www.kadero.online \
  --email admin@kadero.online --agree-tos

# Результат:
# /etc/letsencrypt/live/kadero.online/fullchain.pem
# /etc/letsencrypt/live/kadero.online/privkey.pem
```

### 3. Автообновление сертификата
```bash
# Cron (certbot auto-renew уже настроен через systemd timer)
systemctl enable certbot.timer
systemctl start certbot.timer

# Проверить:
systemctl list-timers | grep certbot
```

### 4. Проверка

```bash
# С любой машины:
curl -I https://kadero.online
# → HTTP/2 200 (или 502 если nginx ещё не настроен — ОК на этом этапе)

# SSL Labs:
# https://www.ssllabs.com/ssltest/analyze.html?d=kadero.online
# Ожидание: A или A+ (после настройки nginx)
```

## Тест-кейсы

| # | Проверка | Ожидание |
|---|----------|----------|
| 1 | `dig kadero.online` | A-запись → IP LB |
| 2 | `curl -I http://kadero.online` | 301 redirect → HTTPS (после настройки nginx) |
| 3 | `curl -I https://kadero.online` | SSL handshake OK, valid cert |
| 4 | `certbot certificates` | kadero.online, expiry > 60 days |
