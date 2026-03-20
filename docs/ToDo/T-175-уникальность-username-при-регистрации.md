# T-175: Глобальная уникальность username + целевая модель регистрации

**Тип:** Bug + Architecture
**Приоритет:** High
**Статус:** Done (Фаза A). Фазы B, C — Open
**Дата анализа:** 2026-03-20

---

## 1. Требования

1. **Username = email.** Логин пользователя — это его email-адрес. Никаких произвольных username.
2. **Email глобально уникален.** Один email = один пользователь во всей системе. Дубликатов быть не может.
3. **Дубликаты = блокировка.** Если в системе обнаружены два пользователя с одинаковым email/username — **обе учётные записи удаляются**. Нельзя давать выбор тенанта, это нарушает кросс-тенантную безопасность.
4. **OAuth → email в профиле.** При входе через Яндекс OAuth email сохраняется в учётной записи и отображается в профиле пользователя.
5. **Приглашения через email.** Владелец тенанта приглашает пользователей по email. Для этого в настройках тенанта — UI для указания реквизитов почтового сервера (SMTP).

---

## 2. Целевая модель

### Принцип: один email = одна учётная запись = доступ к N тенантам

```
┌──────────────────────────────────────────────────────────────┐
│  ПОЛЬЗОВАТЕЛЬ (User)                                          │
│  username: "ivan@company.ru"  (= email, глобально уникален)   │
│  email: "ivan@company.ru"     (отображается в профиле)        │
│  password_hash / OAuth identity                               │
│                                                               │
│  Членства (tenant_memberships):                               │
│  ├── Тенант "Альфа Банк"  → роль: OWNER                      │
│  ├── Тенант "Бета Финанс" → роль: TENANT_ADMIN               │
│  └── Тенант "PRG Platform" → роль: VIEWER                    │
└──────────────────────────────────────────────────────────────┘
```

### Username = Email

| Что | Сейчас | Целевая модель |
|-----|--------|---------------|
| Username | Произвольная строка (3-255 символов) | **Email-адрес** (валидируется как email) |
| Email | Отдельное поле | **= username** (одно и то же значение) |
| Уникальность | Per-tenant `UNIQUE(tenant_id, username)` | **Глобальная** `UNIQUE(username)` |
| При дубликате | Молча логинит первого | **Обе записи удаляются** |

---

## 3. Процесс регистрации

### Путь 1: Владелец создаёт организацию

```
┌─────────────────────────────────────────────────────────┐
│  Сайт → "Зарегистрироваться"                             │
│                                                           │
│  Способ регистрации:                                      │
│  ┌────────────────────┐  ┌─────────────────────────────┐  │
│  │  Яндекс OAuth       │  │  Email + пароль             │  │
│  │  → редирект         │  │  → ввести email             │  │
│  │  → callback         │  │  → OTP на email             │  │
│  │  → email/name/фото  │  │  → подтвердить код          │  │
│  │  → email в профиль  │  │  → задать пароль            │  │
│  └────────┬───────────┘  └─────────────┬───────────────┘  │
│           └──────────┬─────────────────┘                   │
│                      ▼                                     │
│  Система: есть ли User с этим email?                       │
│                                                            │
│  НЕТ                         ДА                            │
│   ↓                           ↓                            │
│  Создать User                Логин в существующий          │
│  (username = email)          аккаунт                       │
│   ↓                           ↓                            │
│  Создать Tenant              "Создать организацию"         │
│  (название, slug)            → новый Tenant                │
│   ↓                          → membership (OWNER)          │
│  Membership (OWNER)                                        │
│   ↓                                                        │
│  JWT (user_id + tenant_id)                                 │
└────────────────────────────────────────────────────────────┘
```

### Путь 2: Владелец приглашает пользователей

```
┌─────────────────────────────────────────────────────────┐
│  Владелец (OWNER) → Настройки → Пользователи            │
│  → "Пригласить пользователя"                             │
│  → Вводит email + выбирает роль                          │
│                                                           │
│  Система: есть ли User с этим email?                      │
│                                                           │
│  НЕТ                              ДА                      │
│   ↓                                ↓                      │
│  Отправить email-приглашение       Добавить membership    │
│  со ссылкой (через SMTP тенанта)   (User → тенант, роль) │
│   ↓                                ↓                      │
│  Человек переходит по ссылке       Уведомление:           │
│  → регистрация (email+пароль)      "Вас добавили в X"     │
│  → автоматически membership        │                      │
│  → роль из приглашения             │                      │
│   ↓                                ↓                      │
│  При следующем логине — видит                             │
│  новый тенант в TenantSwitcher                            │
└───────────────────────────────────────────────────────────┘
```

### Путь 3: Логин

```
1. Ввод email + пароль  ИЛИ  "Войти через Яндекс"
2. Система находит РОВНО ОДНОГО User (глобальная уникальность)
3. JWT с default_tenant_id (последний использованный)
4. Если несколько тенантов — TenantSwitcher в sidebar
5. switchTenant → проверка membership → новый JWT
```

---

## 4. Почтовый сервер (SMTP)

### Принцип: один SMTP для всей платформы

Почтовый сервер — **общий** для всех тенантов. Настраивается разработчиком/DevOps при поставке через конфигурационный файл приложения. Тенанты не управляют SMTP.

### Конфигурация: `application.yml` (auth-service)

```yaml
spring:
  mail:
    host: ${SMTP_HOST:smtp.yandex.ru}
    port: ${SMTP_PORT:465}
    username: ${SMTP_USERNAME:noreply@kadero.ru}
    password: ${SMTP_PASSWORD:}
    properties:
      mail.smtp.auth: true
      mail.smtp.ssl.enable: ${SMTP_SSL:true}
      mail.smtp.starttls.enable: ${SMTP_STARTTLS:false}
    sender-name: ${SMTP_SENDER_NAME:Кадеро}
    from: ${SMTP_FROM:noreply@kadero.ru}
```

### Деплой: env-переменные в k8s

```yaml
# deploy/k8s/envs/test/auth-service.yaml
env:
  - name: SMTP_HOST
    value: "sm16.hosting.reg.ru"
  - name: SMTP_PORT
    value: "465"
  - name: SMTP_USERNAME
    value: "otp@shepaland.ru"
  - name: SMTP_PASSWORD
    valueFrom:
      secretKeyRef:
        name: smtp-credentials
        key: password
  - name: SMTP_FROM
    value: "otp@shepaland.ru"
  - name: SMTP_SENDER_NAME
    value: "Кадеро"
```

### Использование в коде

```java
@Service
public class EmailService {
    private final JavaMailSender mailSender;
    private final String fromAddress;   // из application.yml
    private final String senderName;    // из application.yml

    // Единый метод отправки — используется для приглашений, OTP, уведомлений
    public void send(String to, String subject, String htmlBody) {
        MimeMessage msg = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
        helper.setFrom(new InternetAddress(fromAddress, senderName));
        helper.setTo(to);
        helper.setSubject(subject);
        helper.setText(htmlBody, true);
        mailSender.send(msg);
    }
}
```

**Нет UI для SMTP.** Нет API для SMTP. Нет таблицы в БД. Только конфигурационный файл + env-переменные.

---

## 5. Обработка дубликатов

### Политика: дубликаты = удаление обеих записей

Если при проверке обнаружены два пользователя с одинаковым email/username:

```
1. Системный cron (или при деплое миграции):
   SELECT email, COUNT(*) FROM users
   WHERE is_active = true GROUP BY email HAVING COUNT(*) > 1

2. Для каждого дубликата:
   - Деактивировать ОБЕ записи (is_active = false)
   - Записать в audit_log: "DUPLICATE_USERNAME_BLOCKED"
   - Пользователь при логине увидит:
     "Ваша учётная запись заблокирована. Обнаружен конфликт имени пользователя.
      Обратитесь к администратору платформы."

3. Администратор платформы (SUPER_ADMIN):
   - Получает уведомление о дубликатах
   - Разрешает вручную: удаляет одну, переименовывает другую
   - Или: связывается с пользователями для уточнения
```

### Текущие дубликаты на test — план разрешения

| Username | Тенанты | Решение |
|----------|---------|---------|
| `superadmin` (5 шт.) | PRG Platform + 4 тестовых | Оставить в PRG Platform. Удалить остальные (тестовые тенанты). |
| `Shepelkin.a@yandex.ru` (3 шт.) | Тест 1, 2, 3 | Оставить в Тест 1 (первый). Удалить из Тест 2, 3. Создать membership вместо дубликатов (Фаза B). |
| `shepaland` (2 шт.) | PRG Platform, Супер компания | Оставить в PRG Platform. Удалить из Супер компания. Username `shepaland` → переименовать в email-формат. |

**Для `shepaland`:** username не в формате email. После требования "username = email" нужно:
- Установить username = email пользователя (например `shepelkin.a@yandex.ru`)
- Или: запросить у пользователя актуальный email

---

## 6. Миграция существующих username в email-формат

### Проблема: не все username — email

```sql
-- Текущие username на test:
superadmin                    ← НЕ email
shepaland                     ← НЕ email
maksim                        ← НЕ email
test                          ← НЕ email
Shepelkin.a@yandex.ru         ← email ✓
user1@test.com                ← email ✓
```

### План миграции

```sql
-- V43__username_is_email.sql

-- 1. Для пользователей где username != email — установить username = email
UPDATE users SET username = LOWER(TRIM(email))
WHERE LOWER(TRIM(username)) != LOWER(TRIM(email));

-- 2. Нормализация
UPDATE users SET
    username = LOWER(TRIM(email)),
    email = LOWER(TRIM(email));

-- 3. Удалить per-tenant constraints
ALTER TABLE users DROP CONSTRAINT IF EXISTS uq_users_tenant_username;
ALTER TABLE users DROP CONSTRAINT IF EXISTS uq_users_tenant_email;

-- 4. Глобальный UNIQUE
ALTER TABLE users ADD CONSTRAINT uq_users_username_global UNIQUE (username);
ALTER TABLE users ADD CONSTRAINT uq_users_email_global UNIQUE (email);

-- 5. Check: username = email (enforce equality)
ALTER TABLE users ADD CONSTRAINT chk_username_is_email CHECK (username = email);
```

**ВАЖНО:** Перед шагом 4 — разрешить все дубликаты (иначе миграция упадёт).

---

## 7. Изменения в коде

### Backend

| Файл | Что менять |
|------|-----------|
| `User.java` | `@PrePersist`: `username = email = LOWER(TRIM(email))`. CHECK constraint. |
| `CreateUserRequest.java` | Убрать поле `username`. Username = email автоматически. |
| `UserService.createUser()` | `existsByEmail()` глобально. Username = email. try-catch DataIntegrity. |
| `AuthService.login()` | Искать по email (= username): `findByEmailAndIsActiveTrue()`. Один результат. |
| `OnboardingService.onboard()` | Username = email из OAuth identity. Проверка глобальной уникальности. |
| `UserRepository.java` | `existsByEmail(String)`, `findByEmailAndIsActiveTrue(String)`. |
| `JwtTokenProvider.java` | `claims.put("email", email)` — email всегда в JWT. |

### Frontend

| Файл | Что менять |
|------|-----------|
| `LoginPage.tsx` | Поле "Email" вместо "Логин". Валидация email-формата. |
| `ProfilePage.tsx` | Отображать email из JWT/профиля. |
| `UserCreateModal.tsx` | Убрать поле "Логин". Только email + пароль + роль. |
| `InviteUserModal.tsx` | НОВЫЙ: модалка приглашения по email. |

### Новые API

| Метод | Endpoint | Описание |
|-------|----------|----------|
| POST | `/api/v1/users/invite` | Пригласить по email (отправка через общий SMTP платформы) |

---

## 8. Фазы реализации

### Фаза A: Username = Email + глобальная уникальность (быстрый фикс)

| # | Задача | Приоритет | Сложность |
|---|--------|-----------|-----------|
| A.0 | Разрешить дубликаты вручную (SQL) | **Critical** | Ручная работа |
| A.1 | V43: username = email + глобальный UNIQUE | **Critical** | Миграция SQL |
| A.2 | Backend: убрать username из CreateUserRequest, auto-set из email | **Critical** | ~30 строк |
| A.3 | Backend: login по email (один результат) | **Critical** | ~10 строк |
| A.4 | Backend: @PrePersist нормализация + CHECK | **High** | ~10 строк |
| A.5 | Backend: try-catch DataIntegrityViolation → 409 | **High** | ~15 строк |
| A.6 | Frontend: LoginPage — "Email" вместо "Логин" | **High** | ~5 строк |
| A.7 | Frontend: UserCreateModal — убрать поле "Логин" | **High** | ~10 строк |

### Фаза B: Tenant memberships (архитектура)

| # | Задача | Приоритет | Сложность |
|---|--------|-----------|-----------|
| B.1 | Таблица `tenant_memberships` + миграция данных | **Medium** | Высокая |
| B.2 | Рефакторинг User entity (убрать tenant FK) | **Medium** | Высокая |
| B.3 | switchTenant через membership | **Medium** | Средняя |
| B.4 | Onboarding: membership вместо нового User | **Medium** | Средняя |

### Фаза C: Приглашения по email

| # | Задача | Приоритет | Сложность |
|---|--------|-----------|-----------|
| C.1 | SMTP конфигурация в `application.yml` + env-переменные | **Medium** | Низкая |
| C.2 | `EmailService` — общий сервис отправки (Spring Mail) | **Medium** | Низкая (~30 строк) |
| C.3 | API: POST /users/invite (отправка приглашения) | **Medium** | Средняя |
| C.4 | Email-шаблон приглашения (HTML) | **Medium** | Низкая |
| C.5 | Frontend: страница принятия приглашения | **Medium** | Средняя |

---

## 9. Риски

### Риск 1: Миграция V43 упадёт при дубликатах (КРИТИЧЕСКИЙ)

`UNIQUE(username)` невозможен пока есть дубликаты. Этап A.0 **строго до** A.1.

### Риск 2: username != email у существующих пользователей (ВЫСОКИЙ)

`superadmin`, `shepaland`, `maksim`, `test` — не email. Миграция `SET username = email` сработает только если email корректен. Для `superadmin` email = `admin@prg-platform.com` — ОК.

Но: после миграции `superadmin` логинится как `admin@prg-platform.com`, а не `superadmin`. **Нужно уведомить**.

### Риск 3: switchTenant сломается (ВЫСОКИЙ)

Текущий switchTenant для password-users ищет другого User с тем же username. После глобальной уникальности таких User'ов нет.

**Mitigation:** В Фазе A — switchTenant только через OAuth links. В Фазе B — через memberships.

### Риск 4: Prod — дубликаты неизвестны (ВЫСОКИЙ)

Prod-сервер недоступен (host key changed). Перед деплоем — обязательно проверить.

### Риск 5: SMTP недоступен на test (Yandex Cloud блокирует) (СРЕДНИЙ)

На test-стейджинге (Yandex Cloud) исходящий SMTP заблокирован. Приглашения по email не будут работать на test.

**Mitigation:** Для test — логировать email в console вместо отправки. Или: использовать SMTP через внешний relay (не Yandex Cloud).

### Риск 6: Фаза B — большой рефакторинг (АРХИТЕКТУРНЫЙ)

Изменение модели User затрагивает все контроллеры, JWT, ABAC. Делать **после** стабилизации Фазы A.

---

## 10. Тест-кейсы

| # | Сценарий | Ожидаемый результат |
|---|----------|-------------------|
| 1 | Регистрация: email `ivan@mail.ru` | User создан, username = `ivan@mail.ru` |
| 2 | Регистрация: email `ivan@mail.ru` (повтор) | **409 Conflict**: "Email already registered" |
| 3 | Создание пользователя админом: email `op1@company.ru` | User создан, username = `op1@company.ru` |
| 4 | Создание: email `op1@company.ru` в ДРУГОМ тенанте | **409 Conflict** (глобальная уникальность) |
| 5 | Логин: `ivan@mail.ru` + пароль | Однозначная авторизация |
| 6 | Логин: `IVAN@Mail.Ru` + пароль | Авторизация (case-insensitive) |
| 7 | OAuth Яндекс → email `ivan@mail.ru` | Логин в существующий аккаунт, email в профиле |
| 8 | Приглашение нового пользователя | Email-приглашение через общий SMTP → регистрация → membership |
| 11 | Приглашение существующего пользователя | Добавляется membership, уведомление |
| 12 | Дубликат обнаружен в БД | Обе записи деактивированы, audit log |
