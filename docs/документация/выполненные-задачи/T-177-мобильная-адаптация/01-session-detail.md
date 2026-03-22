# T-177.1: SessionDetailPage — grid-cols-3 → responsive

**Приоритет:** Critical
**Сложность:** Низкая (~3 строки)
**Файл:** `web-dashboard/src/pages/SessionDetailPage.tsx`

---

## Проблема

Три карточки метрик (Segments, Duration, Size) в `grid-cols-3` без мобильного fallback. На 375px: (375-32)/3 ≈ 114px на карточку — текст обрезается, элементы наползают.

## Исправление

```tsx
// БЫЛО:
<div className="grid grid-cols-3 gap-4 mb-6">

// СТАЛО:
<div className="grid grid-cols-1 sm:grid-cols-3 gap-4 mb-6">
```

Также проверить:
- Видеоплеер: должен быть `w-full aspect-video`
- Кнопки управления (Play, Download): touch target ≥ 44px
- Метаданные сессии: `flex-col` на мобильных

## Тест-кейсы

| # | Устройство | Действие | Ожидаемый результат | Скриншот |
|---|-----------|----------|---------------------|----------|
| 1 | iPhone SE (375px) | Открыть детали сессии | Карточки метрик в 1 колонку, текст читаем | `QA/screenshots/T-177.1.1-iphone-se.png` |
| 2 | iPhone 14 (390px) | Открыть детали сессии | Карточки в 1 колонку | `QA/screenshots/T-177.1.2-iphone14.png` |
| 3 | iPad Mini (744px) | Открыть детали сессии | Карточки в 3 колонки | `QA/screenshots/T-177.1.3-ipad.png` |
| 4 | iPhone SE | Воспроизвести видео | Плеер на полную ширину, controls видны | `QA/screenshots/T-177.1.4-player.png` |
| 5 | iPhone SE | Нажать кнопку Download | Touch target достаточный, нажатие срабатывает | |
