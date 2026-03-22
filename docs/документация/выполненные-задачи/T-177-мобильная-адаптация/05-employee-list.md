# T-177.5: EmployeeListPage — sidebar групп → collapsible на мобильных

**Приоритет:** High
**Сложность:** Высокая (~50 строк)
**Файл:** `web-dashboard/src/pages/EmployeeListPage.tsx`

---

## Проблема

Боковая панель с деревом групп сотрудников + таблица. На мобильных sidebar закрывается (MainLayout), но дерево групп на этой странице — inline sidebar слева, не MainLayout sidebar.

Результат: дерево групп + таблица пытаются уместиться в 375px → всё сжимается.

## Исправление

### Мобильный паттерн: кнопка "Группы" → slide-over panel

```tsx
// Mobile: кнопка открывает panel
<div className="lg:hidden mb-4">
  <button onClick={() => setGroupPanelOpen(true)} className="btn-secondary w-full">
    <FunnelIcon className="h-5 w-5 mr-2" />
    Группы {selectedGroup ? `(${selectedGroup.name})` : ''}
  </button>
</div>

{/* Mobile slide-over */}
{groupPanelOpen && (
  <div className="fixed inset-0 z-40 lg:hidden">
    <div className="fixed inset-0 bg-gray-600/75" onClick={() => setGroupPanelOpen(false)} />
    <div className="fixed inset-y-0 left-0 w-72 bg-white shadow-xl p-4 overflow-y-auto">
      <GroupTree />
    </div>
  </div>
)}

{/* Desktop: inline sidebar */}
<div className="hidden lg:block w-64 shrink-0">
  <GroupTree />
</div>

{/* Таблица: на мобильных — полная ширина */}
<div className="flex-1 min-w-0">
  <DataTable />
</div>
```

### Таблица сотрудников
Скрыть на мобильных: колонку "Должность", "Группа" (уже выбрана через фильтр).

## Тест-кейсы

| # | Устройство | Действие | Ожидаемый результат | Скриншот |
|---|-----------|----------|---------------------|----------|
| 1 | iPhone SE | Открыть сотрудников | Таблица на полную ширину, кнопка "Группы" сверху | `QA/screenshots/T-177.5.1-mobile.png` |
| 2 | iPhone SE | Нажать "Группы" | Slide-over panel с деревом групп | `QA/screenshots/T-177.5.2-panel.png` |
| 3 | iPhone SE | Выбрать группу в panel | Panel закрывается, таблица фильтруется | |
| 4 | iPad Mini | Открыть сотрудников | Sidebar inline слева, таблица справа | `QA/screenshots/T-177.5.4-ipad.png` |
| 5 | iPhone SE | Нажать на сотрудника | Переход на детальную страницу | |
