# Glass UI для Windows Agent

## Задача
Переделать UI Windows-агента (C#, WinForms) в стиле "glass" (как iOS 26):
- Полупрозрачный фон с размытием (Acrylic/Mica)
- Скруглённые углы
- Современная типографика
- Окно без стандартной рамки (borderless), draggable
- Позиция по умолчанию: правый нижний угол экрана, не перекрывая панель задач

## Архитектура (без изменений)
- Windows Service записывает (Session 0)
- Tray UI для конфигурирования (user session)
- Named Pipe IPC между ними

## Затронутые файлы

| Файл | Изменения |
|------|-----------|
| `Tray/GlassHelper.cs` | **НОВЫЙ** — Win32 API для Acrylic blur, скруглённых углов, DWM |
| `Tray/StatusWindow.cs` | Glass-стиль, borderless, drag, позиция bottom-right |
| `Tray/SetupForm.cs` | Glass-стиль, borderless, drag, позиция center |
| `Tray/AboutDialog.cs` | Glass-стиль, borderless, drag |
| `Tray/TrayApplication.cs` | Без изменений |
| `Tray/TrayIcons.cs` | Без изменений |

## Glass-эффект: реализация

### Win32 API
- `DwmSetWindowAttribute` — скруглённые углы (DWMWA_WINDOW_CORNER_PREFERENCE = 33)
- `SetWindowCompositionAttribute` — Acrylic blur (AccentState = ACCENT_ENABLE_ACRYLICBLURBEHIND)
- Fallback для Windows 10: полупрозрачный фон без blur

### Визуальные параметры
- Background: `Color.FromArgb(200, 28, 28, 32)` — тёмный полупрозрачный
- Accent color: `#E53935` (красный, Alfa-Bank)
- Text primary: `#FFFFFF`
- Text secondary: `Color.FromArgb(180, 255, 255, 255)`
- Section headers: `#E53935`
- Input fields: `Color.FromArgb(60, 255, 255, 255)` фон, белый текст
- Buttons: gradient `#E53935` → `#B71C1C`
- Corner radius: 12px (DWM)
- Border: 1px `Color.FromArgb(60, 255, 255, 255)`

### Draggable window
- FormBorderStyle = None
- Custom title bar с кнопкой закрытия
- Mouse drag через WM_NCHITTEST или MouseDown/MouseMove/MouseUp
- Позиция: `Screen.PrimaryScreen.WorkingArea` — bottom-right corner

## Позиционирование StatusWindow
```
WorkingArea = Screen.PrimaryScreen.WorkingArea (исключает Taskbar)
X = WorkingArea.Right - WindowWidth
Y = WorkingArea.Bottom - WindowHeight
```

## Миграции БД
Не требуются.

## API изменения
Нет.
