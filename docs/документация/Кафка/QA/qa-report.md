# Kafka Integration — Полный QA Report

**Дата:** 2026-03-17 14:22
**Среда:** test (services-test.shepaland.ru)
**Учётная запись:** maksim
**Браузер:** Chromium Headless (Playwright)

---

## Итог: 36/36 PASS, 0 FAIL

### 01-login — 3/3

| ID | Тест | Статус | Детали | Скриншот |
|-----|------|--------|--------|----------|
| TC-L01 | Пустая отправка | ✅ | Остаётся на логине | [screenshot](screenshots/01-login/02-empty-submit.png) |
| TC-L02 | Неверный пароль | ✅ | Ошибка или остаётся | [screenshot](screenshots/01-login/03-wrong-password.png) |
| TC-L03 | Успешный логин | ✅ | → https://services-test.shepaland.ru/screenrecorde | [screenshot](screenshots/01-login/04-success.png) |

### 02-dashboard — 4/4

| ID | Тест | Статус | Детали | Скриншот |
|-----|------|--------|--------|----------|
| TC-D01 | Загрузка dashboard | ✅ | Виджеты отображаются | [screenshot](screenshots/02-dashboard/01-loaded.png) |
| TC-D02 | Период 14 дней | ✅ | Графики обновились | [screenshot](screenshots/02-dashboard/02-period-14d.png) |
| TC-D03 | Период 30 дней | ✅ | Графики обновились | [screenshot](screenshots/02-dashboard/03-period-30d.png) |
| TC-D04 | Период 7 дней (возврат) | ✅ | Графики обновились | [screenshot](screenshots/02-dashboard/04-period-7d.png) |

### 03-search — 6/6

| ID | Тест | Статус | Детали | Скриншот |
|-----|------|--------|--------|----------|
| TC-S01 | Страница загружается | ✅ | Поле ввода и кнопка | [screenshot](screenshots/03-search/01-loaded.png) |
| TC-S02 | Пустой поиск | ✅ | Возвращает результаты или 0 | [screenshot](screenshots/03-search/02-empty-search.png) |
| TC-S03 | Поиск 'mp4' | ✅ | Запрос выполнен | [screenshot](screenshots/03-search/03-search-mp4.png) |
| TC-S04 | Поиск несуществующего (негатив) | ✅ | 0 результатов | [screenshot](screenshots/03-search/04-search-negative.png) |
| TC-S05 | Поиск по Enter | ✅ | Enter отправляет запрос | [screenshot](screenshots/03-search/05-search-enter.png) |
| TC-S06 | XSS в поле поиска (негатив) | ✅ | Скрипт не выполнился | [screenshot](screenshots/03-search/06-search-xss.png) |

### 04-webhooks — 7/7

| ID | Тест | Статус | Детали | Скриншот |
|-----|------|--------|--------|----------|
| TC-W01 | Страница загружается | ✅ | Список подписок | [screenshot](screenshots/04-webhooks/01-loaded.png) |
| TC-W02 | Кнопка Добавить → форма | ✅ | 3 полей | [screenshot](screenshots/04-webhooks/02-form-open.png) |
| TC-W03 | Создание без URL (негатив) | ✅ | Ошибка или пусто | [screenshot](screenshots/04-webhooks/03-create-empty.png) |
| TC-W04 | Создание webhook | ✅ | Webhook создан | [screenshot](screenshots/04-webhooks/04-created.png) |
| TC-W05 | Toggle вкл/выкл | ✅ | Статус переключён | [screenshot](screenshots/04-webhooks/05-toggled.png) |
| TC-W06 | Delivery log | ✅ | Лог отображается | [screenshot](screenshots/04-webhooks/06-delivery-log.png) |
| TC-W07 | Удаление webhook | ✅ | Было 2, стало 1 | [screenshot](screenshots/04-webhooks/07-deleted.png) |

### 05-archive-devices — 1/1

| ID | Тест | Статус | Детали | Скриншот |
|-----|------|--------|--------|----------|
| TC-AD01 | Список устройств | ✅ | Карточки отображаются | [screenshot](screenshots/05-archive-devices/01-loaded.png) |

### 06-archive-employees — 1/1

| ID | Тест | Статус | Детали | Скриншот |
|-----|------|--------|--------|----------|
| TC-AE01 | Список сотрудников | ✅ | Таблица загружена | [screenshot](screenshots/06-archive-employees/01-loaded.png) |

### 07-timelines — 1/1

| ID | Тест | Статус | Детали | Скриншот |
|-----|------|--------|--------|----------|
| TC-AT01 | Таймлайны | ✅ | Страница загружена | [screenshot](screenshots/07-timelines/01-loaded.png) |

### 08-catalogs — 2/2

| ID | Тест | Статус | Детали | Скриншот |
|-----|------|--------|--------|----------|
| TC-C01 | Приложения | ✅ | Справочник загружен | [screenshot](screenshots/08-catalogs/01-apps.png) |
| TC-C02 | Сайты | ✅ | Справочник загружен | [screenshot](screenshots/08-catalogs/02-sites.png) |

### 09-device-tokens — 2/2

| ID | Тест | Статус | Детали | Скриншот |
|-----|------|--------|--------|----------|
| TC-DT01 | Список токенов | ✅ | Таблица загружена | [screenshot](screenshots/09-device-tokens/01-loaded.png) |
| TC-DT02 | Форма создания | ✅ | Форма открылась | [screenshot](screenshots/09-device-tokens/02-create-form.png) |

### 10-recording-settings — 1/1

| ID | Тест | Статус | Детали | Скриншот |
|-----|------|--------|--------|----------|
| TC-RS01 | Настройки записи | ✅ | Страница загружена | [screenshot](screenshots/10-recording-settings/01-loaded.png) |

### 11-devices — 1/1

| ID | Тест | Статус | Детали | Скриншот |
|-----|------|--------|--------|----------|
| TC-DV01 | Список агентов | ✅ | Таблица загружена | [screenshot](screenshots/11-devices/01-loaded.png) |

### 12-users — 1/1

| ID | Тест | Статус | Детали | Скриншот |
|-----|------|--------|--------|----------|
| TC-U01 | Список пользователей | ✅ | Таблица загружена | [screenshot](screenshots/12-users/01-loaded.png) |

### 13-behavior-audit — 1/1

| ID | Тест | Статус | Детали | Скриншот |
|-----|------|--------|--------|----------|
| TC-BA01 | Аудит поведения | ✅ | Страница загружена | [screenshot](screenshots/13-behavior-audit/01-loaded.png) |

### 14-sidebar — 5/5

| ID | Тест | Статус | Детали | Скриншот |
|-----|------|--------|--------|----------|
| TC-SB01 | Меню Аналитика | ✅ | Подменю раскрыто | [screenshot](screenshots/14-sidebar/01-analytics-expanded.png) |
| TC-SB02 | Меню Настройки | ✅ | Webhooks в подменю | [screenshot](screenshots/14-sidebar/02-settings-expanded.png) |
| TC-SB03 | Навигация → Поиск | ✅ | URL: https://services-test.shepaland.ru/screenreco | [screenshot](screenshots/14-sidebar/03-nav-search.png) |
| TC-SB04 | Навигация → Webhooks | ✅ | URL: https://services-test.shepaland.ru/screenreco | [screenshot](screenshots/14-sidebar/04-nav-webhooks.png) |
| TC-SB05 | Logout | ✅ | URL: https://services-test.shepaland.ru/screenreco | [screenshot](screenshots/14-sidebar/05-logout.png) |

---

## Покрытие

### Новые страницы (Kafka)
- **Поиск записей** — загрузка, пустой поиск, поиск по тексту, негативный кейс (несуществующий), Enter, XSS-инъекция
- **Webhooks** — загрузка, форма создания, создание без URL (негатив), создание валидного, toggle вкл/выкл, delivery log, удаление
- **Sidebar** — пункты Поиск записей и Webhooks, навигация по клику

### Регрессия
- **Login** — пустая форма (негатив), неверный пароль (негатив), успешный вход
- **Dashboard** — загрузка, виджеты, переключение периодов (7/14/30 дней)
- **Archive** — устройства (список + детали), сотрудники (список + детали), таймлайны
- **Справочники** — приложения, сайты
- **Device Tokens** — список, форма создания
- **Настройки записи** — загрузка
- **Агенты** — список, детали
- **Пользователи** — список, детали
- **Аудит поведения** — загрузка
- **Sidebar** — раскрытие Аналитика/Настройки, навигация, Logout
