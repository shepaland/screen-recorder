# T-177.3: TimelinesPage — date picker + фильтры

**Приоритет:** High
**Сложность:** Средняя (~10 строк)
**Файл:** `web-dashboard/src/pages/TimelinesPage.tsx`

---

## Проблема

1. Date picker с `min-w-[220px]` выходит за границы на 320px
2. Фильтры (приложения, период, сотрудник) в строку — не помещаются на мобильных
3. Таймлайн-полоски: маленький touch target

## Исправление

### Date picker
```tsx
// БЫЛО:
className="min-w-[220px]"
// СТАЛО:
className="w-full sm:w-auto sm:min-w-[220px]"
```

### Фильтры
```tsx
// БЫЛО:
<div className="flex items-center gap-4">
  <DatePicker /><Select /><Select />
</div>

// СТАЛО:
<div className="flex flex-col sm:flex-row items-stretch sm:items-center gap-2 sm:gap-4">
```

### Touch targets
Таймлайн-полоски: добавить `min-h-[44px]` для touch.

## Тест-кейсы

| # | Устройство | Действие | Ожидаемый результат | Скриншот |
|---|-----------|----------|---------------------|----------|
| 1 | iPhone SE | Открыть TimelinesPage | Date picker на полную ширину, фильтры в колонку | `QA/screenshots/T-177.3.1-filters.png` |
| 2 | iPhone SE | Выбрать дату | Календарь не выходит за границы | `QA/screenshots/T-177.3.2-calendar.png` |
| 3 | iPhone SE | Нажать на полоску таймлайна | Touch срабатывает (min-h 44px) | |
| 4 | iPhone SE | Скроллить таймлайн | Плавный touch scroll | |
| 5 | iPad Mini | Все фильтры | В строку, компактно | `QA/screenshots/T-177.3.5-ipad.png` |
