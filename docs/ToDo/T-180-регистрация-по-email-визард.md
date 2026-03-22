# T-180: Регистрация нового пользователя — два параллельных трека (Email + Яндекс)

**Тип:** Task
**Приоритет:** High
**Статус:** Open
**Ветка:** `feature/email-registration`

---

## Цель

Реализовать два параллельных трека регистрации нового пользователя:
1. **Email-визард** — ввод данных → пароль → подтверждение по ссылке
2. **Яндекс OAuth** — существующий флоу (без изменений)

Треки работают независимо, но если OAuth-пользователь установил пароль в профиле — он может логиниться и через email+пароль.

---

## Текущее состояние

### Email-регистрация (OTP)
1. Пользователь вводит email → получает 6-значный код → вводит код → создаётся тенант + пользователь без пароля (OWNER)
2. Пароль можно установить позже через профиль
3. Нет ввода имени/фамилии/компании на этапе регистрации
4. Нет валидации сложности пароля

### Яндекс OAuth
1. Пользователь нажимает "Войти через Яндекс" → redirect на Яндекс → callback → onboarding (ввод названия компании) → создаётся тенант + пользователь (`auth_provider = "oauth"`)
2. **Проблема**: OAuth-пользователи полностью заблокированы от входа по паролю (`AuthService.java:102`), даже если пароль установлен

### Проблема кроссовера OAuth → password login

```java
// AuthService.java:102 — текущий код
if ("oauth".equals(user.getAuthProvider())) {
    throw new AccessDeniedException("This account uses OAuth authentication...");
}
```

Блокировка по `auth_provider` слишком грубая. Правильно проверять `is_password_set`:
- `is_password_set = false` → блокировать парольный вход (нет пароля)
- `is_password_set = true` → разрешить парольный вход (независимо от auth_provider)

**Ключевые файлы (текущие):**
- `auth-service/.../controller/EmailRegistrationController.java` — 3 эндпоинта OTP (initiate/verify/resend)
- `auth-service/.../controller/OAuthController.java` — Яндекс OAuth (redirect/callback/onboarding/select-tenant)
- `auth-service/.../service/EmailOtpService.java` — OTP-логика
- `auth-service/.../service/OAuthService.java` — OAuth-логика
- `auth-service/.../service/AuthService.java` — парольный логин (строка 102 — блокировка OAuth)
- `auth-service/.../service/EmailService.java` — отправка писем
- `web-dashboard/src/pages/LoginPage.tsx` — UI (credentials → email-input → email-code)
- `web-dashboard/src/api/auth.ts` — API-функции

---

## Два параллельных трека регистрации

### Страница входа (`/login`) — макет

```
┌─────────────────────────────────────┐
│              Кадеро                 │
│                                     │
│  ┌───────────────────────────────┐  │
│  │ Email                         │  │
│  └───────────────────────────────┘  │
│  ┌───────────────────────────────┐  │
│  │ Пароль                    👁  │  │
│  └───────────────────────────────┘  │
│                                     │
│  ┌───────────────────────────────┐  │
│  │           Войти               │  │
│  └───────────────────────────────┘  │
│                                     │
│  ─────────── или ───────────────    │
│                                     │
│  ┌───────────────────────────────┐  │
│  │    Я  Войти через Яндекс     │  │
│  └───────────────────────────────┘  │
│                                     │
│  ───────────────────────────────    │
│                                     │
│       Нет аккаунта? Создать →       │
│                                     │
└─────────────────────────────────────┘
```

- **Верх**: email + пароль + кнопка "Войти" — парольный вход (для email- и OAuth-пользователей с паролем)
- **Середина**: разделитель "или" + "Войти через Яндекс" — OAuth
- **Низ**: ссылка "Нет аккаунта? Создать" → `/register` (Email-визард)

### Страница регистрации (`/register`) — визард

**Шаг 1 — Данные:**
```
┌─────────────────────────────────────┐
│           Регистрация               │
│                                     │
│  ┌───────────────────────────────┐  │
│  │ Email *                       │  │
│  └───────────────────────────────┘  │
│  ┌───────────────────────────────┐  │
│  │ Имя                          │  │
│  └───────────────────────────────┘  │
│  ┌───────────────────────────────┐  │
│  │ Фамилия                      │  │
│  └───────────────────────────────┘  │
│  ┌───────────────────────────────┐  │
│  │ Название компании *           │  │
│  └───────────────────────────────┘  │
│                                     │
│  ┌───────────────────────────────┐  │
│  │           Далее               │  │
│  └───────────────────────────────┘  │
│                                     │
│  ─────────── или ───────────────    │
│                                     │
│  ┌───────────────────────────────┐  │
│  │    Я  Войти через Яндекс     │  │
│  └───────────────────────────────┘  │
│                                     │
│     Уже есть аккаунт? Войти →       │
│                                     │
└─────────────────────────────────────┘
```

**Шаг 2 — Пароль:**
```
┌─────────────────────────────────────┐
│        Придумайте пароль            │
│                                     │
│  ┌───────────────────────────────┐  │
│  │ Пароль                    👁  │  │
│  └───────────────────────────────┘  │
│  ██████████░░░░  Средний            │
│  Мин. 8 символов, буквы и цифры     │
│                                     │
│  ┌───────────────────────────────┐  │
│  │ Подтверждение пароля      👁  │  │
│  └───────────────────────────────┘  │
│                                     │
│        🔑 Сгенерировать пароль      │
│                                     │
│  ┌─────────┐ ┌───────────────────┐  │
│  │  Назад  │ │ Зарегистрироваться│  │
│  └─────────┘ └───────────────────┘  │
│                                     │
└─────────────────────────────────────┘
```

**Шаг 3 — Подтверждение:**
```
┌─────────────────────────────────────┐
│         Проверьте почту             │
│                                     │
│           ✉️  📨                     │
│                                     │
│  Мы отправили ссылку для            │
│  подтверждения на                   │
│  user@example.com                   │
│                                     │
│  Ссылка действительна 24 часа       │
│                                     │
│  ┌───────────────────────────────┐  │
│  │     Отправить повторно (58)   │  │
│  └───────────────────────────────┘  │
│                                     │
│        Изменить email →             │
│                                     │
└─────────────────────────────────────┘
```

Два способа регистрации доступны параллельно:
1. **"Далее"** на шаге 1 → Email-визард (`/register`)
2. **"Войти через Яндекс"** на шаге 1 → Яндекс OAuth (существующий флоу)

### Трек 1: Email-визард (НОВЫЙ)

```
[Email + Имя + Компания] → [Пароль] → [Письмо] → [Клик по ссылке] → Главная
```

- `auth_provider = "password"`, `is_password_set = true`, `email_verified = false` → `true`
- Подробное описание ниже

### Трек 2: Яндекс OAuth (СУЩЕСТВУЮЩИЙ, без изменений)

```
[Яндекс OAuth] → [Callback] → [Onboarding: название компании] → Главная
```

- `auth_provider = "oauth"`, `is_password_set = false`, `email_verified = true`
- Код: `OAuthController.java`, `OAuthService.java`, `OnboardingService.java`

### Кроссовер: OAuth-пользователь → вход по паролю

**Сценарий:** пользователь зарегистрировался через Яндекс, затем установил пароль в профиле → хочет логиниться по email+пароль.

**Текущее поведение:** заблокирован (`auth_provider == "oauth"` → AccessDeniedException)

**Целевое поведение:**

| auth_provider | is_password_set | Вход по паролю | Вход через Яндекс |
|---------------|----------------|----------------|-------------------|
| `password`    | `true`         | Да             | Нет (нет OAuth-линка) |
| `oauth`       | `false`        | Нет (нет пароля) | Да              |
| `oauth`       | `true`         | **Да**         | Да               |
| `email`       | `false`        | Нет (нет пароля) | Нет             |
| `email`       | `true`         | **Да**         | Нет             |

**Изменение в `AuthService.java`** (строка 102):

```java
// БЫЛО:
if ("oauth".equals(user.getAuthProvider())) {
    throw new AccessDeniedException("This account uses OAuth authentication...");
}

// СТАЛО:
if (!Boolean.TRUE.equals(user.getIsPasswordSet())) {
    throw new AccessDeniedException(
        "Password not set. Please log in via OAuth or set a password in your profile.",
        "PASSWORD_NOT_SET");
}
```

Логика: неважно, как пользователь зарегистрировался — если пароль установлен, парольный вход разрешён.

---

## Трек 1: Email-визард (подробно)

### Шаг 1. Ввод данных (фронтенд)

**Поля:**
| Поле | Обязательное | Валидация |
|------|-------------|-----------|
| Email | Да | Формат email, проверка disposable-доменов (бэкенд) |
| Имя | Нет | Макс. 100 символов, trim |
| Фамилия | Нет | Макс. 100 символов, trim |
| Название компании | Да | Мин. 2 символа, макс. 200 символов, trim |

**UI:**
- Заголовок: "Регистрация"
- Поля вводятся на одном экране
- Кнопка "Далее" → переход к Шагу 2
- Ссылка "Уже есть аккаунт? Войти" → возврат на логин

### Шаг 2. Создание пароля (фронтенд)

**Поля:**
| Поле | Валидация |
|------|-----------|
| Пароль | Мин. 8 символов, обязательно буквы И цифры |
| Подтверждение пароля | Совпадение с паролем |

**UI:**
- Заголовок: "Придумайте пароль"
- Индикатор сложности пароля (слабый / средний / сильный)
- Правила под полем: "Минимум 8 символов, буквы и цифры"
- Кнопка "Сгенерировать пароль" — генерирует случайный пароль (12 символов, буквы+цифры+спецсимволы), вставляет в оба поля, показывает пароль
- Иконка "глаз" для показа/скрытия пароля
- Кнопка "Назад" → возврат к Шагу 1 (данные сохраняются)
- Кнопка "Зарегистрироваться" → отправка на бэкенд

**Правила сложности пароля:**
- Минимум 8 символов
- Обязательно хотя бы 1 буква (a-z, A-Z)
- Обязательно хотя бы 1 цифра (0-9)
- Индикатор:
  - Слабый (красный): < 8 символов или нет букв/цифр
  - Средний (жёлтый): 8-11 символов, буквы + цифры
  - Сильный (зелёный): 12+ символов или есть спецсимволы

### Шаг 3. Подтверждение email (бэкенд + фронтенд)

**После нажатия "Зарегистрироваться":**
1. Бэкенд создаёт пользователя, тенант, хеширует пароль
2. Генерирует verification token (UUID, TTL 24 часа)
3. Отправляет email со ссылкой: `{FRONTEND_BASE_URL}/verify-email/{token}`
4. Фронтенд показывает экран "Проверьте почту":
   - "Мы отправили ссылку для подтверждения на **user@example.com**"
   - "Ссылка действительна 24 часа"
   - Кнопка "Отправить повторно" (с cooldown 60 секунд)
   - Ссылка "Изменить email" → возврат к Шагу 1

### Шаг 4. Подтверждение по ссылке (бэкенд)

**Пользователь кликает ссылку из email:**
1. GET `/api/v1/auth/verify-email/{token}` — бэкенд проверяет токен
2. Если валидный: `email_verified = true`, генерирует JWT-токены
3. Редирект на `{FRONTEND_BASE_URL}/` с токенами (через query-параметр или cookie)
4. Фронтенд подхватывает токены → пользователь залогинен → главная страница

**Если токен невалидный/истёк:**
- Редирект на `{FRONTEND_BASE_URL}/verify-email-expired`
- Страница: "Ссылка истекла или недействительна. Зарегистрируйтесь заново."

---

## Backend: API

### Новые эндпоинты

#### POST `/api/v1/auth/register`

Создание пользователя + отправка verification email.

**Request:**
```json
{
  "email": "user@example.com",
  "password": "MyPass123",
  "first_name": "Иван",
  "last_name": "Петров",
  "company_name": "Моя компания"
}
```

**Валидация (бэкенд):**
- `email`: обязательно, формат email, не disposable, не занят
- `password`: обязательно, мин. 8 символов, хотя бы 1 буква + 1 цифра
- `company_name`: обязательно, 2-200 символов
- `first_name`, `last_name`: опционально, макс. 100 символов

**Response 200:**
```json
{
  "message": "Verification email sent",
  "email": "user@example.com"
}
```

**Ошибки:**
| Код | Code | Описание |
|-----|------|----------|
| 400 | INVALID_EMAIL | Некорректный email |
| 400 | DISPOSABLE_EMAIL | Одноразовый email |
| 400 | WEAK_PASSWORD | Пароль не соответствует требованиям |
| 400 | INVALID_COMPANY_NAME | Название компании слишком короткое/длинное |
| 409 | EMAIL_ALREADY_EXISTS | Email уже зарегистрирован |
| 429 | TOO_MANY_REQUESTS | Rate limit |

**Логика:**
1. Валидация полей
2. Проверка уникальности email
3. Создание тенанта (name = company_name, slug = auto-generated)
4. Копирование системных ролей из template tenant
5. Создание пользователя (auth_provider = "password", email_verified = false, is_password_set = true)
6. Назначение роли OWNER
7. Генерация verification token → сохранение в БД
8. Отправка email с ссылкой подтверждения
9. Аудит: `EMAIL_REGISTER_INITIATED`

#### GET `/api/v1/auth/verify-email/{token}`

Подтверждение email по ссылке.

**Response:** HTTP 302 Redirect
- Успех: → `{FRONTEND_BASE_URL}/?verified=true&token={jwt_access_token}`
- Ошибка: → `{FRONTEND_BASE_URL}/verify-email-expired`

**Логика:**
1. Найти verification token в БД
2. Проверить: не использован, не истёк (24 часа)
3. `email_verified = true`
4. Пометить token как использованный
5. Сгенерировать JWT access + refresh tokens
6. Установить refresh_token cookie
7. Redirect с access_token
8. Аудит: `EMAIL_VERIFIED`

#### POST `/api/v1/auth/register/resend-verification`

Повторная отправка verification email.

**Request:**
```json
{
  "email": "user@example.com"
}
```

**Response 200:**
```json
{
  "message": "Verification email sent",
  "resend_available_in": 60
}
```

**Ограничения:**
- Cooldown 60 секунд между отправками
- Макс. 5 отправок за 1 час
- Только для email_verified = false

---

## Backend: БД

### Новая таблица `email_verification_tokens`

```sql
CREATE TABLE email_verification_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token VARCHAR(255) NOT NULL UNIQUE,
    is_used BOOLEAN NOT NULL DEFAULT FALSE,
    expires_at TIMESTAMPTZ NOT NULL,
    created_ts TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_evt_token ON email_verification_tokens(token);
CREATE INDEX idx_evt_user_id ON email_verification_tokens(user_id);
```

### Flyway миграция

**V38__email_verification_tokens.sql** (auth-service):
- Создание таблицы `email_verification_tokens`

---

## Backend: Email-шаблон

### Письмо подтверждения регистрации

**Subject:** "Подтвердите регистрацию — Кадеро"

**Тело (HTML):**
- Логотип "Кадеро"
- Текст: "Здравствуйте, {first_name}! Для завершения регистрации нажмите кнопку ниже."
- Кнопка: "Подтвердить email" → ссылка `{FRONTEND_BASE_URL}/verify-email/{token}`
- Мелкий текст: "Ссылка действительна 24 часа. Если вы не регистрировались, проигнорируйте это письмо."
- Футер: "Это автоматическое сообщение от платформы Кадеро."

---

## Frontend: компоненты

### Новые файлы

| Файл | Назначение |
|------|-----------|
| `pages/RegisterPage.tsx` | Визард регистрации (шаги 1-3) |
| `components/PasswordStrengthIndicator.tsx` | Индикатор сложности пароля |
| `components/PasswordGenerator.tsx` | Генератор пароля (кнопка) |
| `pages/VerifyEmailPage.tsx` | Обработка редиректа `/verify-email/{token}` |
| `pages/VerifyEmailExpiredPage.tsx` | Страница "Ссылка истекла" |

### Изменения в существующих файлах

| Файл | Изменение |
|------|----------|
| `api/auth.ts` | Добавить: `register()`, `resendVerification()` |
| `App.tsx` / роутинг | Добавить маршруты: `/register`, `/verify-email/:token`, `/verify-email-expired` |
| `LoginPage.tsx` | Добавить ссылку "Создать аккаунт" → `/register` |

### RegisterPage.tsx — состояния визарда

```
step: 'profile' | 'password' | 'confirmation'
```

- **profile**: email, first_name, last_name, company_name → "Далее"
- **password**: password, confirm_password, strength indicator, generate button → "Зарегистрироваться"
- **confirmation**: "Проверьте почту", resend button с таймером

### VerifyEmailPage.tsx

- При монтировании: забирает `token` из URL, вызывает `GET /verify-email/{token}`
- Бэкенд делает redirect → фронтенд получает JWT через query-параметр `?token=...`
- Сохраняет токен в localStorage/context → редирект на главную

---

## Безопасность

1. **Verification token**: UUID v4, одноразовый, TTL 24 часа
2. **Пароль**: bcrypt (strength 12), валидация на бэкенде дублирует фронтенд
3. **Rate limiting**: per-IP (20 req / 5 min), per-email cooldown (60 sec)
4. **Disposable email**: проверка через существующую таблицу `disposable_email_domains`
5. **Email нормализация**: lowercase, удаление +tag, gmail dot-trick (существующая логика)
6. **CSRF**: verification token одноразовый, привязан к user_id
7. **Аудит**: все действия логируются (initiate, verified, resend, failed)

---

## Что сохраняется от текущей реализации

- `EmailService` — добавится метод `sendVerificationLink()` (рядом с существующими `sendVerificationCode()` и `sendInvitation()`)
- `DisposableEmailDomainRepository` — используется как есть
- Email нормализация из `EmailOtpService` — вынести в утилиту или переиспользовать
- Rate limiting паттерн (per-IP, per-email) — аналогичный

## Что НЕ меняется

- **Яндекс OAuth** (`OAuthController`, `OAuthService`, `OnboardingService`) — без изменений, работает параллельно
- **Mail.ru OAuth** — без изменений
- **InvitationService** — без изменений
- OTP-флоу (`/register/initiate`, `/register/verify`, `/register/resend`) — **остаётся** как альтернативный способ входа для существующих пользователей

## Что меняется в существующем коде

- **`AuthService.java`** (строка 102): заменить проверку `auth_provider == "oauth"` на `is_password_set == false` — разрешить парольный вход OAuth-пользователям с установленным паролем
- **`LoginPage.tsx`**: добавить ссылку "Создать аккаунт" → `/register`

---

## План реализации

### Фаза 1. Backend

1. **AuthService.java**: фикс кроссовера — заменить проверку `auth_provider == "oauth"` на `is_password_set == false`
2. **V38 миграция**: таблица `email_verification_tokens`
3. **Entity + Repository**: `EmailVerificationToken`, `EmailVerificationTokenRepository`
4. **PasswordValidator**: утилита валидации сложности пароля (мин. 8, буквы+цифры)
5. **EmailRegistrationService**: логика регистрации (create tenant + user + send verification)
6. **EmailService**: метод `sendVerificationLink()`
7. **RegisterController**: `POST /register`, `GET /verify-email/{token}`, `POST /register/resend-verification`
8. **SecurityConfig**: добавить новые эндпоинты в `permitAll`

### Фаза 2. Frontend

1. **RegisterPage.tsx**: визард (3 шага)
2. **PasswordStrengthIndicator.tsx**: компонент индикатора
3. **VerifyEmailPage.tsx**: обработка подтверждения
4. **VerifyEmailExpiredPage.tsx**: страница ошибки
5. **api/auth.ts**: новые API-функции
6. **Роутинг**: новые маршруты
7. **LoginPage.tsx**: ссылка "Создать аккаунт"

### Фаза 3. Деплой и тестирование

1. Деплой auth-service на test
2. Деплой web-dashboard на test
3. Smoke-тесты (см. тест-кейсы)

---

## Тест-кейсы

| # | Тест-кейс | Ожидание |
|---|-----------|----------|
| 1 | Регистрация с валидными данными | Тенант + пользователь созданы, email отправлен |
| 2 | Клик по ссылке подтверждения | email_verified=true, редирект на главную, пользователь залогинен |
| 3 | Повторный клик по ссылке | Ошибка "Ссылка уже использована" |
| 4 | Клик по ссылке через 25 часов | Редирект на /verify-email-expired |
| 5 | Регистрация с занятым email | 409 EMAIL_ALREADY_EXISTS |
| 6 | Регистрация с disposable email | 400 DISPOSABLE_EMAIL |
| 7 | Пароль < 8 символов | Ошибка валидации (фронт + бэк) |
| 8 | Пароль без цифр | Ошибка валидации |
| 9 | Пароль без букв | Ошибка валидации |
| 10 | Генерация пароля | Пароль соответствует требованиям, вставлен в оба поля |
| 11 | Повторная отправка verification | Email отправлен, cooldown 60 сек |
| 12 | Rate limit: 20+ запросов | 429 TOO_MANY_REQUESTS |
| 13 | Вход по паролю после верификации | Успешный логин |
| 14 | Попытка входа до верификации | Ошибка "Email не подтверждён" |
| 15 | Навигация назад в визарде | Данные шага 1 сохранены |
| **Кроссовер OAuth → password** | | |
| 16 | OAuth-пользователь без пароля → вход по email+пароль | Ошибка "Пароль не установлен" |
| 17 | OAuth-пользователь установил пароль → вход по email+пароль | Успешный логин |
| 18 | OAuth-пользователь с паролем → вход через Яндекс | Успешный логин (оба способа работают) |
| 19 | Email-пользователь → вход через Яндекс | Не работает (нет OAuth-линка) |

---

## Оценка изменений

| Компонент | Файлов | Тип |
|-----------|--------|-----|
| AuthService.java (кроссовер фикс) | 1 | изменение (1 строка) |
| Flyway миграция | 1 | новый |
| Entity + Repository | 2 | новый |
| PasswordValidator | 1 | новый |
| EmailRegistrationService | 1 | новый |
| RegisterController | 1 | новый (или расширение существующего) |
| EmailService | 1 | изменение (+ метод) |
| SecurityConfig | 1 | изменение (permitAll) |
| Frontend компоненты | 5 | новые |
| api/auth.ts | 1 | изменение |
| Роутинг + LoginPage | 2 | изменение |
| **Итого** | **~17** | |
