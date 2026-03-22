# T-177: Декомпозиция — Мобильная адаптация web-dashboard

**Родительская задача:** `docs/ToDo/T-177-мобильная-адаптация-веба.md`

## Подзадачи

| # | Задача | Приоритет | Сложность | Файл |
|---|--------|-----------|-----------|------|
| T-177.1 | SessionDetailPage: grid-cols-3 → responsive | Critical | Низкая | `01-session-detail.md` |
| T-177.2 | UserReportsPage: tab-nav overflow | High | Средняя | `02-user-reports.md` |
| T-177.3 | TimelinesPage: date picker + фильтры | High | Средняя | `03-timelines.md` |
| T-177.4 | DeviceTokensListPage: скрытие колонок | High | Средняя | `04-device-tokens.md` |
| T-177.5 | EmployeeListPage: sidebar → collapsible | High | Высокая | `05-employee-list.md` |
| T-177.6 | DevicesListPage: sidebar → collapsible | High | Высокая | `06-devices-list.md` |
| T-177.7 | SearchPage: фильтры в колонку | Medium | Средняя | `07-search.md` |
| T-177.8 | Таблицы (Audit, Recordings, Roles, Tenants, Settings): скрытие колонок | Medium | Средняя | `08-таблицы-скрытие-колонок.md` |
| T-177.9 | Формы + Dashboard + DeviceGrid: проверка touch targets | Low | Низкая | `09-формы-и-виджеты.md` |

## Порядок реализации

```
T-177.1 (SessionDetail, critical)
    ↓
T-177.2 (UserReports)  ──┐
T-177.3 (Timelines)    ──┤── параллельно
T-177.4 (DeviceTokens) ──┘
    ↓
T-177.5 (EmployeeList)  ──┐── параллельно (общий паттерн sidebar)
T-177.6 (DevicesList)   ──┘
    ↓
T-177.7 (Search)
T-177.8 (Таблицы — bulk)
T-177.9 (Формы — проверка)
```

## Тестирование

Каждая подзадача содержит тест-кейсы. Тестировать в Chrome DevTools Device Toolbar:
- iPhone SE (375px) — минимальный
- iPhone 14 (390px) — средний
- iPad Mini (744px) — планшет

Скриншоты: `QA/screenshots/T-177.X.Y-описание.png`
