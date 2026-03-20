# Фаза 8: Исправление установки, permissions и Status Bar

## Цель

Исправить три критических недочёта: мусорные процессы при установке, неработающий запуск после выдачи Screen Recording permission, отсутствие иконки в Status Bar.

## Зависимости

- Фаза 6 (UI, установка, автозапуск)
- Текущий инсталлятор и main.swift

## Проблемы и решения

### Проблема 1: Мусорные агенты в Applications

**Симптом:** Десятки процессов KaderoAgent в Activity Monitor.

**Корневая причина:** LaunchAgent с `KeepAlive: true` бесконечно перезапускает агент при crash. Агент крашится при первом запуске, если:
- Нет credentials (ещё не зарегистрирован) → `exit(1)`
- Нет Screen Recording permission → `SCStream` throws error → crash
- osascript dialog отменён → `exit(1)`

Каждый перезапуск = новый crash = launchd пытается снова (throttle 10s по умолчанию).

**Решение:**

1. **LaunchAgent: заменить `KeepAlive: true` на `KeepAlive: SuccessfulExit`** — перезапуск только при exit(0) (graceful restart по команде), НЕ при crash (exit(1))

```xml
<key>KeepAlive</key>
<dict>
    <key>SuccessfulExit</key>
    <false/>
</dict>
<key>ThrottleInterval</key>
<integer>30</integer>
```

2. **Агент: не крашиться при отсутствии credentials** — если нет stdin и GUI-диалог отменён, выйти с кодом 1 (LaunchAgent не перезапустит)

3. **Агент: при ошибке записи (нет permission) — не crash, а перейти в состояние "waiting_permission"** с периодической проверкой

4. **Первый запуск: установка LaunchAgent ТОЛЬКО после успешной регистрации**, не при каждом старте

---

### Проблема 2: Не запускается после выдачи Screen Recording permission

**Симптом:** Пользователь выдаёт разрешение Screen Recording → агент всё равно не записывает.

**Корневая причина:** macOS кеширует permissions per-process. `CGPreflightScreenCaptureAccess()` / `SCShareableContent` возвращают cached результат. Нужен перезапуск процесса.

**Решение:**

1. **Перед стартом записи — проверить permissions:**

```swift
import ScreenCaptureKit

func hasScreenCapturePermission() -> Bool {
    // macOS 15+
    if #available(macOS 15, *) {
        return CGPreflightScreenCaptureAccess()
    }
    // macOS 13-14: попробовать получить список экранов
    let semaphore = DispatchSemaphore(value: 0)
    var hasAccess = false
    Task {
        do {
            let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: true)
            hasAccess = !content.displays.isEmpty
        } catch { hasAccess = false }
        semaphore.signal()
    }
    semaphore.wait()
    return hasAccess
}
```

2. **Если нет permission — запросить и показать инструкцию:**

```swift
func requestScreenCapturePermission() {
    CGRequestScreenCaptureAccess()
}
```

3. **Периодическая проверка (polling):** если permission не дан — проверять каждые 10 секунд. Как только дан — выполнить `exit(0)` для graceful restart через LaunchAgent (с `KeepAlive: SuccessfulExit: false`)

4. **GUI-уведомление** при первом запуске: "Необходимо разрешение Screen Recording. Откройте System Settings → Privacy & Security → Screen Recording → включите KaderoAgent. После этого агент перезапустится автоматически."

---

### Проблема 3: Нет иконки в Status Bar

**Симптом:** Агент работает в фоне, но пользователь не видит его и не может управлять.

**Корневая причина:** Текущий main.swift использует `dispatchMain()` (GCD run loop) вместо `NSApplication`. Нет `NSStatusItem`.

**Решение:**

1. **Заменить `dispatchMain()` на `NSApplication.shared.run()`** — это запускает AppKit event loop, необходимый для NSStatusItem

2. **Создать `StatusBarController`:**

```swift
import AppKit

class StatusBarController {
    private var statusItem: NSStatusItem
    private let menu = NSMenu()

    init() {
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        setupMenu()
        updateIcon(status: "online")
    }

    func updateIcon(status: String) {
        let icon: String
        switch status {
        case "recording": icon = "⏺"  // или SF Symbol "record.circle"
        case "error": icon = "⚠️"
        case "recording_disabled": icon = "⏸"
        case "offline": icon = "○"
        default: icon = "●"  // online
        }
        statusItem.button?.title = icon
    }

    private func setupMenu() {
        menu.addItem(NSMenuItem(title: "Кадеро v1.0.0", action: nil, keyEquivalent: ""))
        menu.addItem(NSMenuItem(title: "Статус: Онлайн", action: nil, keyEquivalent: ""))
        menu.addItem(NSMenuItem.separator())
        menu.addItem(NSMenuItem(title: "Начать запись", action: #selector(startRecording), keyEquivalent: ""))
        menu.addItem(NSMenuItem(title: "Остановить запись", action: #selector(stopRecording), keyEquivalent: ""))
        menu.addItem(NSMenuItem.separator())
        menu.addItem(NSMenuItem(title: "Открыть логи...", action: #selector(openLogs), keyEquivalent: ""))
        menu.addItem(NSMenuItem.separator())
        menu.addItem(NSMenuItem(title: "Выход", action: #selector(quit), keyEquivalent: "q"))
        statusItem.menu = menu
    }
}
```

3. **Иконки состояния:**

| Состояние | SF Symbol | Fallback | Цвет |
|-----------|-----------|----------|------|
| `online` | `circle.fill` | ● | Серый |
| `recording` | `record.circle` | ⏺ | Красный |
| `error` | `exclamationmark.triangle` | ⚠ | Жёлтый |
| `recording_disabled` | `pause.circle` | ⏸ | Серый |
| `offline` | `circle` | ○ | Серый |

4. **Интеграция с main.swift:**

```swift
// Заменить:
// dispatchMain()

// На:
let app = NSApplication.shared
let appDelegate = AppDelegate()
app.delegate = appDelegate
app.run()
```

5. **`AppDelegate`** запускает все сервисы в `applicationDidFinishLaunching`, создаёт StatusBarController, обновляет статус при изменении `currentStatus`.

## Задачи

1. Создать `StatusBarController` — NSStatusItem + NSMenu с командами
2. Создать `AppDelegate` — NSApplicationDelegate, запуск сервисов
3. Создать `PermissionChecker` — проверка/запрос Screen Recording + Accessibility
4. Переработать main.swift: `NSApplication.shared.run()` вместо `dispatchMain()`
5. Исправить LaunchAgent: `KeepAlive: SuccessfulExit: false` + `ThrottleInterval: 30`
6. Исправить installLaunchAgent() — новый формат plist
7. Добавить polling permissions: если нет → wait + notify → exit(0) для restart
8. Обновить build-installer.sh и пересобрать DMG

## macOS-специфика

### NSApplication в CLI-бинарнике

`NSApplication.shared.run()` работает в executable target (не .app bundle) — AppKit подключается через import. Но для корректной работы NSStatusItem и NSMenu бинарник ДОЛЖЕН быть в .app bundle с Info.plist.

### Screen Recording permission flow

```
Первый запуск:
  ├── CGRequestScreenCaptureAccess() → macOS показывает промпт
  ├── Пользователь идёт в System Settings → даёт разрешение
  ├── Агент каждые 10с проверяет CGPreflightScreenCaptureAccess()
  ├── Permission получен → exit(0)
  └── LaunchAgent (SuccessfulExit: false) → перезапускает → запись стартует
```

### Accessibility permission flow

```
AXIsProcessTrustedWithOptions([kAXTrustedCheckOptionPrompt: true])
  └── macOS показывает промпт → пользователь добавляет в System Settings
```

## Критерии приёмки

1. При установке создаётся максимум 1 процесс KaderoAgent, без бесконечных респавнов
2. LaunchAgent с `SuccessfulExit: false` — перезапуск только при exit(0)
3. После выдачи Screen Recording permission — агент автоматически перезапускается и начинает запись
4. Иконка в Status Bar отображает текущее состояние (online/recording/error/disabled)
5. Контекстное меню: статус, start/stop recording, открыть логи, выход
6. При отмене GUI-диалога регистрации — exit(1), LaunchAgent не перезапускает
7. Permissions проверяются при старте, показывается инструкция если нет
