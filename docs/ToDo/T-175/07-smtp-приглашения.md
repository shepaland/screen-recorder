# T-175.7: SMTP конфигурация + EmailService + приглашения по email

**Фаза:** C
**Приоритет:** Medium
**Зависит от:** T-175.2
**Блокирует:** —

---

## Цель

Владелец тенанта может пригласить пользователя по email. Система отправляет приглашение через общий SMTP платформы.

---

## Шаги реализации

### Шаг 1: SMTP конфигурация (application.yml)

```yaml
# auth-service/src/main/resources/application.yml
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

kadero:
  mail:
    from: ${SMTP_FROM:noreply@kadero.ru}
    sender-name: ${SMTP_SENDER_NAME:Кадеро}
    invite-url-base: ${INVITE_URL_BASE:https://services-test.shepaland.ru/screenrecorder}
```

### Шаг 2: EmailService.java

```java
@Service
public class EmailService {
    private final JavaMailSender mailSender;
    @Value("${kadero.mail.from}") private String fromAddress;
    @Value("${kadero.mail.sender-name}") private String senderName;

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

### Шаг 3: InvitationService.java

```java
@Service
public class InvitationService {
    // Таблица invitations для отслеживания
    // При приглашении:
    //   1. Проверить: User с этим email уже есть?
    //      ДА → добавить membership (если Фаза B) или сообщить что User уже существует
    //      НЕТ → создать invitation, отправить email
    //   2. Сгенерировать invite_token (UUID), сохранить в БД (expires_at = 7 дней)
    //   3. Отправить email со ссылкой: {base_url}/invite/{invite_token}

    public void invite(UUID tenantId, String email, UUID roleId, UUID invitedBy) {
        // Проверить глобальную уникальность
        if (userRepository.existsByEmail(email.toLowerCase().trim())) {
            // Фаза A: ошибка "User already exists"
            // Фаза B: добавить membership и уведомить
            throw new ConflictException("User with this email already exists");
        }

        Invitation inv = Invitation.builder()
            .tenantId(tenantId).email(email.toLowerCase().trim())
            .roleId(roleId).invitedBy(invitedBy)
            .token(UUID.randomUUID().toString())
            .expiresAt(Instant.now().plus(7, ChronoUnit.DAYS))
            .build();
        invitationRepository.save(inv);

        String link = inviteUrlBase + "/invite/" + inv.getToken();
        String tenantName = tenantRepository.findById(tenantId).map(Tenant::getName).orElse("");
        emailService.send(email,
            "Приглашение в " + tenantName + " — Кадеро",
            buildInviteHtml(tenantName, link));
    }
}
```

### Шаг 4: Таблица invitations

```sql
-- V45__invitations.sql
CREATE TABLE invitations (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    email       VARCHAR(255) NOT NULL,
    role_id     UUID NOT NULL REFERENCES roles(id),
    token       VARCHAR(255) NOT NULL UNIQUE,
    invited_by  UUID NOT NULL REFERENCES users(id),
    accepted_ts TIMESTAMPTZ,
    expires_at  TIMESTAMPTZ NOT NULL,
    created_ts  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_invitations_token ON invitations(token) WHERE accepted_ts IS NULL;
```

### Шаг 5: API

```java
// POST /api/v1/users/invite
@PostMapping("/invite")
public ResponseEntity<Void> invite(
    @Valid @RequestBody InviteRequest request,   // { email, role_id }
    @AuthenticationPrincipal UserPrincipal principal) {
    requirePermission(principal, "USERS:CREATE");
    invitationService.invite(
        principal.getTenantId(), request.getEmail(),
        request.getRoleId(), principal.getUserId());
    return ResponseEntity.ok().build();
}

// GET /api/v1/auth/invite/{token} — страница принятия приглашения
// POST /api/v1/auth/invite/{token}/accept — регистрация по приглашению
```

### Шаг 6: Frontend — InviteUserModal

В UsersListPage — кнопка "Пригласить":
- Модалка: поле Email + выбор Роли + кнопка "Отправить приглашение"
- После отправки: уведомление "Приглашение отправлено на email@..."

### Шаг 7: Frontend — AcceptInvitePage

Страница `/invite/{token}`:
- Показывает: "Вас пригласили в организацию {name}"
- Форма: Email (readonly, из invitation), Пароль, Имя, Фамилия
- Кнопка: "Зарегистрироваться и войти"
- Или: "Войти через Яндекс" (если хочет OAuth)

---

## Ключевые файлы

| Файл | Действие |
|------|----------|
| `auth-service/src/main/resources/application.yml` | SMTP конфигурация |
| `auth-service/.../service/EmailService.java` | НОВЫЙ — отправка email через Spring Mail |
| `auth-service/.../service/InvitationService.java` | НОВЫЙ — логика приглашений |
| `auth-service/.../entity/Invitation.java` | НОВЫЙ entity |
| `auth-service/.../controller/UserController.java` | POST /users/invite |
| `auth-service/.../controller/AuthController.java` | GET/POST /auth/invite/{token} |
| `auth-service/.../db/migration/V45__invitations.sql` | НОВЫЙ |
| `web-dashboard/src/components/InviteUserModal.tsx` | НОВЫЙ |
| `web-dashboard/src/pages/AcceptInvitePage.tsx` | НОВЫЙ |

---

## Тест-кейсы

| # | ID | Шаг | Ожидаемый результат | Скриншот |
|---|----|-----|---------------------|----------|
| 1 | TC-175.7.1 | Chromium: Пользователи → "Пригласить" | Модалка с полями Email, Роль | `QA/screenshots/T-175.7.1-invite-modal.png` |
| 2 | TC-175.7.2 | Ввести email `newuser@test.com`, роль OPERATOR → "Отправить" | Уведомление "Приглашение отправлено" | `QA/screenshots/T-175.7.2-invite-sent.png` |
| 3 | TC-175.7.3 | Проверить: invitation в БД | `SELECT * FROM invitations WHERE email = 'newuser@test.com'` — есть | |
| 4 | TC-175.7.4 | Ввести email существующего пользователя | Ошибка "Пользователь уже зарегистрирован" (Фаза A) или авто-membership (Фаза B) | `QA/screenshots/T-175.7.4-exists.png` |
| 5 | TC-175.7.5 | Chromium: открыть `/invite/{token}` | Страница "Вас пригласили в {tenant}" | `QA/screenshots/T-175.7.5-accept-page.png` |
| 6 | TC-175.7.6 | Заполнить пароль, имя → "Зарегистрироваться" | User создан, membership добавлен, редирект на Dashboard | `QA/screenshots/T-175.7.6-registered.png` |
| 7 | TC-175.7.7 | Повторно открыть тот же `/invite/{token}` | "Приглашение уже использовано" | `QA/screenshots/T-175.7.7-used.png` |
| 8 | TC-175.7.8 | Открыть `/invite/{expired_token}` (просроченный) | "Приглашение истекло" | `QA/screenshots/T-175.7.8-expired.png` |
| 9 | TC-175.7.9 | SMTP недоступен (test-стейджинг) | Email логируется в console, invitation создаётся | |
| 10 | TC-175.7.10 | Проверить email пришёл (prod с рабочим SMTP) | Письмо с ссылкой в почтовом ящике | Скриншот email |
