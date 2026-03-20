# T-175.3: Backend — логин по email (один результат)

**Фаза:** A
**Приоритет:** Critical
**Зависит от:** T-175.2
**Блокирует:** T-175.4

---

## Цель

Логин всегда находит ровно одного пользователя. Никакого выбора тенанта. Email = единственный идентификатор.

---

## Шаги реализации

### Шаг 1: AuthService.login() — заменить поиск

```java
// БЫЛО:
List<User> candidates = userRepository.findActivePasswordUsersByUsername(request.getUsername());
if (candidates.isEmpty()) { user = null; }
else { user = candidates.get(0); } // первый из нескольких!

// СТАЛО:
String normalizedEmail = request.getUsername().trim().toLowerCase();
Optional<User> found = userRepository.findByEmailAndIsActiveTrue(normalizedEmail);
User user = found.orElse(null);
Tenant tenant = user != null ? user.getTenant() : null;
```

### Шаг 2: LoginRequest.java — переименовать поле

```java
// БЫЛО:
@NotBlank private String username;

// СТАЛО:
@NotBlank @Email(message = "Invalid email format")
private String email;
```

Для обратной совместимости: принимать и `username`, и `email` поле (Jackson alias).

### Шаг 3: Удалить findActivePasswordUsersByUsername()

```java
// UserRepository.java — УДАЛИТЬ:
// List<User> findActivePasswordUsersByUsername(String username);

// ОСТАВИТЬ:
Optional<User> findByEmailAndIsActiveTrue(String email);
```

### Шаг 4: Обработка ошибки дубликата при логине

Если каким-то образом дубликат остался (legacy data) — **не авторизовывать**:

```java
// Дополнительная защита (на случай если constraint не срабатывает):
long count = userRepository.countByEmailAndIsActiveTrue(normalizedEmail);
if (count > 1) {
    log.error("SECURITY: duplicate email detected: {}", normalizedEmail);
    // Деактивировать все записи с этим email
    userRepository.deactivateAllByEmail(normalizedEmail);
    throw new AuthenticationException("Account blocked due to duplicate. Contact administrator.");
}
```

---

## Ключевые файлы

| Файл | Действие |
|------|----------|
| `auth-service/.../service/AuthService.java` | login(): поиск по email, один результат |
| `auth-service/.../dto/request/LoginRequest.java` | `email` вместо `username`, @Email валидация |
| `auth-service/.../repository/UserRepository.java` | Удалить `findActivePasswordUsersByUsername`, добавить `countByEmailAndIsActiveTrue` |

---

## Тест-кейсы

| # | ID | Шаг | Ожидаемый результат | Скриншот |
|---|----|-----|---------------------|----------|
| 1 | TC-175.3.1 | POST /api/v1/auth/login с email `admin@prg-platform.com` + правильный пароль | 200 OK, JWT с tenant_id | |
| 2 | TC-175.3.2 | POST /api/v1/auth/login с email `admin@prg-platform.com` + неправильный пароль | 401 Unauthorized | |
| 3 | TC-175.3.3 | POST /api/v1/auth/login с email `nonexistent@test.com` | 401 Unauthorized | |
| 4 | TC-175.3.4 | POST /api/v1/auth/login с email `ADMIN@PRG-PLATFORM.COM` (uppercase) | 200 OK (case-insensitive) | |
| 5 | TC-175.3.5 | POST /api/v1/auth/login с email `  admin@prg-platform.com  ` (пробелы) | 200 OK (trim) | |
| 6 | TC-175.3.6 | POST /api/v1/auth/login с невалидным email `notanemail` | 400 Bad Request, validation error | |
| 7 | TC-175.3.7 | JWT после логина: проверить claims (user_id, tenant_id, email, roles, permissions) | Все claims корректны | |
| 8 | TC-175.3.8 | Логин деактивированного пользователя (is_active=false) | 401 Unauthorized | |
| 9 | TC-175.3.9 | Chromium: открыть LoginPage, ввести email + пароль, нажать "Войти" | Редирект на Dashboard | Скриншот |
| 10 | TC-175.3.10 | Chromium: LoginPage — неправильный пароль | Сообщение об ошибке | Скриншот |
| 11 | TC-175.3.11 | Chromium: LoginPage — "Войти через Яндекс" → callback → Dashboard | Успешный OAuth-логин, email в профиле | Скриншот |
