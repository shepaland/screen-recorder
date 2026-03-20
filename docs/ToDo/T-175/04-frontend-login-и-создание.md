# T-175.4: Frontend — LoginPage (email) + UserCreateModal (без username)

**Фаза:** A
**Приоритет:** High
**Зависит от:** T-175.2, T-175.3
**Блокирует:** —

---

## Цель

1. LoginPage: поле "Email" вместо "Логин", валидация email-формата
2. UserCreateModal: убрать поле "Логин", оставить только Email
3. ProfilePage: отображать email

---

## Шаги реализации

### Шаг 1: LoginPage.tsx

- Лейбл поля: `"Логин"` → `"Email"`
- Placeholder: `"Введите логин"` → `"Введите email"`
- Input type: `"text"` → `"email"`
- Валидация: email-формат перед отправкой
- Отправка: поле `email` (или `username` для обратной совместимости) в POST /auth/login

### Шаг 2: UserCreateModal.tsx (или UserCreatePage)

- **Убрать** поле "Логин" / "Username"
- Оставить: Email, Пароль, Имя, Фамилия, Роль
- При отправке: `username` не передаётся (backend auto-set из email)

### Шаг 3: ProfilePage.tsx

- Отображать email из JWT claims или GET /api/v1/users/me
- Поле "Email" — read-only (для смены email нужен отдельный flow с верификацией)

### Шаг 4: UsersListPage.tsx

- Колонка "Логин" → "Email"
- Или: убрать колонку "Логин" полностью (username = email, дублирование)

---

## Ключевые файлы

| Файл | Действие |
|------|----------|
| `web-dashboard/src/pages/LoginPage.tsx` | "Email" вместо "Логин", type="email" |
| `web-dashboard/src/components/UserCreateModal.tsx` | Убрать поле username |
| `web-dashboard/src/pages/ProfilePage.tsx` | Отображать email |
| `web-dashboard/src/pages/UsersListPage.tsx` | Колонка "Email" вместо "Логин" |
| `web-dashboard/src/api/auth.ts` | login(): отправлять `email` вместо `username` |

---

## Тест-кейсы

Все тесты выполняются в **Chromium** на test-стейджинге (`https://services-test.shepaland.ru/screenrecorder`). Каждый шаг сопровождается скриншотом.

### LoginPage

| # | ID | Шаг | Действие в Chromium | Ожидаемый результат | Скриншот |
|---|----|-----|---------------------|---------------------|----------|
| 1 | TC-175.4.1 | Открыть LoginPage | Перейти на `/screenrecorder/login` | Поле "Email" (не "Логин"), placeholder "Введите email" | `QA/screenshots/T-175.4.1-login-page.png` |
| 2 | TC-175.4.2 | Ввести невалидный email | Ввести `notanemail`, нажать "Войти" | Валидация: "Введите корректный email" | `QA/screenshots/T-175.4.2-validation.png` |
| 3 | TC-175.4.3 | Ввести правильный email + пароль | `admin@prg-platform.com` + пароль | Редирект на Dashboard | `QA/screenshots/T-175.4.3-login-success.png` |
| 4 | TC-175.4.4 | Ввести неправильный пароль | `admin@prg-platform.com` + `wrong` | Сообщение "Неверный email или пароль" | `QA/screenshots/T-175.4.4-login-error.png` |
| 5 | TC-175.4.5 | "Войти через Яндекс" | Нажать кнопку OAuth | Редирект на Яндекс → callback → Dashboard | `QA/screenshots/T-175.4.5-oauth.png` |

### Создание пользователя

| # | ID | Шаг | Действие в Chromium | Ожидаемый результат | Скриншот |
|---|----|-----|---------------------|---------------------|----------|
| 6 | TC-175.4.6 | Открыть форму создания | Настройки → Пользователи → "Создать" | Форма: Email, Пароль, Имя, Фамилия, Роль. **НЕТ поля "Логин"** | `QA/screenshots/T-175.4.6-create-form.png` |
| 7 | TC-175.4.7 | Создать пользователя | Заполнить email `test175@test.com`, пароль, роль → "Создать" | Пользователь создан, в списке отображается email | `QA/screenshots/T-175.4.7-user-created.png` |
| 8 | TC-175.4.8 | Создать дубликат | Заполнить email `test175@test.com` → "Создать" | Ошибка: "Email уже зарегистрирован" | `QA/screenshots/T-175.4.8-duplicate-error.png` |
| 9 | TC-175.4.9 | Ввести невалидный email | Ввести `notanemail` → "Создать" | Валидация: "Введите корректный email" | `QA/screenshots/T-175.4.9-email-validation.png` |

### Список пользователей

| # | ID | Шаг | Действие в Chromium | Ожидаемый результат | Скриншот |
|---|----|-----|---------------------|---------------------|----------|
| 10 | TC-175.4.10 | Открыть список пользователей | Настройки → Пользователи | Колонка "Email" (не "Логин"), значения — email-адреса | `QA/screenshots/T-175.4.10-users-list.png` |

### Профиль

| # | ID | Шаг | Действие в Chromium | Ожидаемый результат | Скриншот |
|---|----|-----|---------------------|---------------------|----------|
| 11 | TC-175.4.11 | Открыть профиль | Sidebar → "Мой профиль" | Email отображается, read-only | `QA/screenshots/T-175.4.11-profile.png` |
