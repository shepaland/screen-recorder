# T-175.2: Backend — username=email, глобальная проверка, нормализация

**Фаза:** A
**Приоритет:** Critical
**Зависит от:** T-175.1
**Блокирует:** T-175.3, T-175.4

---

## Цель

Все точки создания пользователей проверяют глобальную уникальность email. Username автоматически = email. Нормализация lowercase+trim.

---

## Шаги реализации

### Шаг 1: Entity User.java — @PrePersist нормализация

```java
@PrePersist @PreUpdate
public void normalize() {
    if (email != null) {
        email = email.trim().toLowerCase();
        username = email;  // username всегда = email
    }
}
```

### Шаг 2: UserRepository.java — глобальные методы

```java
boolean existsByEmail(String email);

Optional<User> findByEmailAndIsActiveTrue(String email);

// Удалить или deprecated:
// boolean existsByTenantIdAndUsername(UUID tenantId, String username);
```

### Шаг 3: UserService.createUser() — глобальная проверка

```java
// Нормализация
String normalizedEmail = request.getEmail().trim().toLowerCase();

// Глобальная проверка (не per-tenant!)
if (userRepository.existsByEmail(normalizedEmail)) {
    throw new DuplicateResourceException(
        "Email already registered in the system", "EMAIL_ALREADY_EXISTS");
}

// Создание User: username = email
User user = User.builder()
    .tenant(tenant)
    .username(normalizedEmail)  // = email
    .email(normalizedEmail)
    ...
    .build();

// Страховка от race condition
try {
    user = userRepository.save(user);
} catch (DataIntegrityViolationException e) {
    if (e.getMessage() != null && e.getMessage().contains("uq_users_username_global")) {
        throw new DuplicateResourceException("Email already registered", "EMAIL_ALREADY_EXISTS");
    }
    if (e.getMessage() != null && e.getMessage().contains("uq_users_email_global")) {
        throw new DuplicateResourceException("Email already registered", "EMAIL_ALREADY_EXISTS");
    }
    throw e;
}
```

### Шаг 4: CreateUserRequest.java — убрать username

```java
// УБРАТЬ поле:
// private String username;

// ОСТАВИТЬ:
@NotBlank @Email
private String email;

@NotBlank @Size(min = 8, max = 128)
@Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).+$")
private String password;

private List<UUID> roleIds;
private String firstName;
private String lastName;
```

### Шаг 5: OnboardingService.onboard() — username = email

```java
User user = User.builder()
    .tenant(tenant)
    .username(identity.getEmail().toLowerCase().trim())  // = email
    .email(identity.getEmail().toLowerCase().trim())
    ...
    .build();

// Глобальная проверка перед save
if (userRepository.existsByEmail(user.getEmail())) {
    throw new DuplicateResourceException("Email already registered", "EMAIL_ALREADY_EXISTS");
}
```

---

## Ключевые файлы

| Файл | Действие |
|------|----------|
| `auth-service/.../entity/User.java` | @PrePersist: username = email, lowercase+trim |
| `auth-service/.../repository/UserRepository.java` | `existsByEmail()`, `findByEmailAndIsActiveTrue()` |
| `auth-service/.../service/UserService.java` | Глобальная проверка + try-catch + username=email |
| `auth-service/.../service/OnboardingService.java` | username = email + глобальная проверка |
| `auth-service/.../dto/request/CreateUserRequest.java` | Убрать поле `username` |

---

## Тест-кейсы

| # | ID | Шаг | Ожидаемый результат | Скриншот |
|---|----|-----|---------------------|----------|
| 1 | TC-175.2.1 | POST /api/v1/users с email `newuser@test.com` (тенант А) | 201 Created, username = `newuser@test.com` | |
| 2 | TC-175.2.2 | POST /api/v1/users с email `newuser@test.com` (тенант Б) | **409 Conflict**, code: `EMAIL_ALREADY_EXISTS` | |
| 3 | TC-175.2.3 | POST /api/v1/users с email `newuser@test.com` (тенант А, повтор) | **409 Conflict** | |
| 4 | TC-175.2.4 | POST /api/v1/users с email `NewUser@Test.Com` | **409 Conflict** (нормализация case-insensitive) | |
| 5 | TC-175.2.5 | POST /api/v1/users с email `  user@test.com  ` (пробелы) | 201 Created, username = `user@test.com` (trim) | |
| 6 | TC-175.2.6 | POST /api/v1/users **без поля username** (только email) | 201 Created (username не требуется в request) | |
| 7 | TC-175.2.7 | GET /api/v1/users/{id} — проверить что username = email | response.username == response.email | |
| 8 | TC-175.2.8 | Race condition: два параллельных POST с одинаковым email | Один 201, другой 409 (не 500) | |
| 9 | TC-175.2.9 | OAuth onboarding с email уже зарегистрированным | **409** или логин в существующий аккаунт | |
| 10 | TC-175.2.10 | Chromium: Настройки → Пользователи → Создать. Нет поля "Логин", только Email | Скриншот формы создания | Скриншот |
