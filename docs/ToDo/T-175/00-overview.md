# T-175: Декомпозиция — Глобальная уникальность username (email)

**Родительская задача:** `docs/ToDo/T-175-уникальность-username-при-регистрации.md`

## Подзадачи

| # | Задача | Фаза | Статус | Зависит от | Файл |
|---|--------|------|--------|------------|------|
| T-175.1 | Разрешение существующих дубликатов + Flyway V43 | A | **Done** | — | `01-миграция-бд.md` |
| T-175.2 | Backend: username=email, глобальная проверка, нормализация | A | **Done** | T-175.1 | `02-backend-уникальность.md` |
| T-175.3 | Backend: логин по email (один результат) | A | **Done** | T-175.2 | `03-backend-логин.md` |
| T-175.4 | Frontend: LoginPage email + UserCreateModal без username | A | **Done** | T-175.2, T-175.3 | `04-frontend-login-и-создание.md` |
| T-175.5 | Таблица tenant_memberships + миграция данных | B | **Done** | T-175.1-4 | `05-tenant-memberships.md` |
| T-175.6 | Backend: switchTenant через membership, onboarding | B | **Done** | T-175.5 | `06-backend-membership.md` |
| T-175.7 | SMTP конфигурация + EmailService + приглашения | C | Open | T-175.2 | `07-smtp-приглашения.md` |

## Порядок реализации

```
T-175.1 (миграция БД)
    ↓
T-175.2 (backend уникальность)
    ↓
T-175.3 (backend логин) ──────► T-175.4 (frontend)
    ↓
T-175.5 (memberships)
    ↓
T-175.6 (switchTenant)
    ↓
T-175.7 (SMTP + приглашения)
```

## Тестирование

Каждая подзадача содержит тест-кейсы. Результаты тестирования — в `QA/`:
- `QA/T-175.1-results.md` — скриншоты и результаты миграции
- `QA/T-175.2-results.md` — API тесты
- `QA/T-175.3-results.md` — логин тесты
- `QA/T-175.4-results.md` — UI скриншоты (Chromium)
- и т.д.
