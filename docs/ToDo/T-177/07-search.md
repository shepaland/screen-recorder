# T-177.7: SearchPage — фильтры в колонку на мобильных

**Приоритет:** Medium
**Сложность:** Средняя (~10 строк)
**Файл:** `web-dashboard/src/pages/SearchPage.tsx`

---

## Проблема

Фильтры поиска (дата, текст, устройство, тип) в строку — не помещаются на 375px. Accordion с результатами — на мобильных контент узкий.

## Исправление

### Фильтры
```tsx
// БЫЛО:
<div className="flex items-center gap-4">

// СТАЛО:
<div className="flex flex-col sm:flex-row items-stretch sm:items-center gap-2 sm:gap-4">
```

### Accordion
Убрать `min-w` если есть. Добавить `break-words` для длинных текстов.

### Кнопка "Поиск"
На мобильных: `w-full`, на desktop: `w-auto`.

## Тест-кейсы

| # | Устройство | Действие | Ожидаемый результат | Скриншот |
|---|-----------|----------|---------------------|----------|
| 1 | iPhone SE | Открыть поиск | Фильтры в колонку, кнопка на полную ширину | `QA/screenshots/T-177.7.1-mobile.png` |
| 2 | iPhone SE | Ввести запрос и искать | Результаты отображаются, accordion работает | `QA/screenshots/T-177.7.2-results.png` |
| 3 | iPhone SE | Раскрыть accordion | Контент на полную ширину, текст не обрезается | |
| 4 | iPad Mini | Открыть поиск | Фильтры в строку | `QA/screenshots/T-177.7.4-ipad.png` |
