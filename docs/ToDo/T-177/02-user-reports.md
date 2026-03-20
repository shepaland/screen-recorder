# T-177.2: UserReportsPage — tab-навигация overflow

**Приоритет:** High
**Сложность:** Средняя (~15 строк)
**Файл:** `web-dashboard/src/pages/UserReportsPage.tsx`

---

## Проблема

Горизонтальные табы (`flex space-x-8`) выходят за границы на мобильных. 5+ табов × ~100px = 500px > 375px. Date range inputs в строку тоже не помещаются.

## Исправление

### Табы
```tsx
// БЫЛО:
<nav className="flex space-x-8 border-b">

// СТАЛО:
<nav className="flex space-x-2 sm:space-x-8 border-b overflow-x-auto -mx-4 px-4 scrollbar-hide">
```

Или: на мобильных заменить на `<select>` dropdown:
```tsx
{/* Mobile: dropdown */}
<div className="sm:hidden">
  <select className="input-field w-full" value={activeTab} onChange={...}>
    <option value="timeline">Таймлайн</option>
    <option value="apps">Приложения</option>
    ...
  </select>
</div>
{/* Desktop: tabs */}
<nav className="hidden sm:flex space-x-8 border-b">...</nav>
```

### Date range
```tsx
// БЫЛО:
<div className="flex items-center gap-2">
  <input type="date" />
  <span>—</span>
  <input type="date" />
</div>

// СТАЛО:
<div className="flex flex-col sm:flex-row items-stretch sm:items-center gap-2">
```

## Тест-кейсы

| # | Устройство | Действие | Ожидаемый результат | Скриншот |
|---|-----------|----------|---------------------|----------|
| 1 | iPhone SE | Открыть UserReportsPage | Табы: dropdown ИЛИ scroll, не обрезаются | `QA/screenshots/T-177.2.1-tabs-mobile.png` |
| 2 | iPhone SE | Переключить таб | Работает, контент обновляется | |
| 3 | iPhone SE | Выбрать период (date) | Поля в колонку, календарь не выходит за край | `QA/screenshots/T-177.2.3-dates.png` |
| 4 | iPad Mini | Открыть страницу | Табы в строку, date inputs в строку | `QA/screenshots/T-177.2.4-ipad.png` |
| 5 | iPhone SE | Просмотреть график | 100% ширины, легенда читаема | `QA/screenshots/T-177.2.5-chart.png` |
