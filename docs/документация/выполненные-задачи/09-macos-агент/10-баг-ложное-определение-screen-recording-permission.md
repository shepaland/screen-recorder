# Баг: Ложное определение Screen Recording permission

## Симптом

В System Settings → Privacy & Security → Screen Recording разрешение выдано для KaderoAgent, но агент показывает ошибку и запись не стартует. В логе:

```
[ERROR] [Main] Failed to start recording: Пользователь отклонил положения и условия захвата приложения, окна или дисплея
```

или пустая ошибка:

```
[ERROR] [Main] Failed to start recording:
```

## Корневая причина

**Расхождение между двумя API проверки permissions на macOS:**

| API | Что проверяет | Результат |
|-----|--------------|-----------|
| `CGWindowListCopyWindowInfo` | Видимость окон других процессов (legacy API) | Может вернуть `true` даже без полного Screen Recording permission |
| `SCShareableContent` / `SCStream.startCapture()` | ScreenCaptureKit permission (TCC) | Бросает ошибку если нет permission для конкретного bundle |
| `CGPreflightScreenCaptureAccess()` (macOS 15+) | TCC запись конкретно | Корректный результат |

Текущая реализация `PermissionChecker.hasScreenCapture` использует `CGWindowListCopyWindowInfo` — проверяет, можно ли увидеть окна других процессов. Этот API **менее строгий**: на некоторых конфигурациях macOS он возвращает окна даже без формального Screen Recording permission (особенно для системных окон вроде "Пункт управления").

В итоге: `hasScreenCapture` → `true` → код идёт дальше → `SCStream.startCapture()` → **бросает ошибку** потому что ScreenCaptureKit проверяет TCC (Transparency, Consent and Control) базу, которая привязана к **конкретному bundle identifier и пути**.

## Дополнительные факторы

### 1. Разные пути бинарника

macOS выдаёт Screen Recording permission конкретному `.app` бандлу. Если агент запускается из:
- `/Applications/KaderoAgent.app/Contents/MacOS/KaderoAgent` — один permission entry
- `.build/release/KaderoAgentCLI` — другой permission entry
- `installer/build/KaderoAgent.app/Contents/MacOS/KaderoAgent` — ещё один

Каждый путь = отдельная запись в TCC. Permission выданный одному не действует для другого.

### 2. Ad-hoc signing

Текущий инсталлятор подписывает `.app` ad-hoc (`codesign --sign -`). macOS использует code signing identity для идентификации приложения в TCC. Ad-hoc sign = каждая пересборка = новый identity = нужно заново давать permission.

### 3. Пустой `localizedDescription`

SCStream ошибка на русской macOS имеет локализованное описание "Пользователь отклонил положения и условия...", но на некоторых macOS-инстансах `error.localizedDescription` может быть пустым, что делает диагностику невозможной.

## Решение

### Исправление 1: Использовать правильный API для проверки permission

```swift
public var hasScreenCapture: Bool {
    // macOS 15+: прямой API
    if #available(macOS 15, *) {
        return CGPreflightScreenCaptureAccess()
    }
    // macOS 13-14: попробовать реально получить контент через ScreenCaptureKit
    // Это единственный надёжный способ
    var result = false
    let group = DispatchGroup()
    group.enter()
    // Запуск в отдельном потоке чтобы избежать deadlock с NSApplication.run()
    DispatchQueue.global().async {
        Task {
            do {
                let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: true)
                result = !content.displays.isEmpty
            } catch {
                result = false
            }
            group.leave()
        }
    }
    _ = group.wait(timeout: .now() + 5)
    return result
}
```

**Ключевое отличие:** используем `SCShareableContent` (тот же API, который потом будет вызван при записи), а не `CGWindowListCopyWindowInfo`.

### Исправление 2: Обработка ошибки SCStream с retry

В `startRecording()`, если `SCStream.startCapture()` бросает ошибку permission:

```swift
} catch {
    let desc = error.localizedDescription
    let isPermissionError = desc.contains("отклонил") || desc.contains("denied") || desc.isEmpty

    if isPermissionError {
        log.error("Screen Recording permission denied by ScreenCaptureKit")
        agentState.status = "waiting_permission"
        agentState.setError("Нет разрешения Screen Recording. Проверьте System Settings → Privacy & Security → Screen Recording")
    } else {
        log.error("Failed to start recording: \(desc)")
        agentState.status = "error"
        agentState.setError(desc)
    }
}
```

### Исправление 3: Логировать полную информацию об ошибке

```swift
log.error("Failed to start recording: '\(error.localizedDescription)' | type: \(type(of: error)) | raw: \(error)")
```

### Исправление 4: Стабильный code signing

При пересборке сохранять одну и ту же signing identity, чтобы macOS не считал каждую сборку новым приложением:

```bash
# Создать self-signed certificate один раз
security create-keychain-pair -k login.keychain "Kadero Dev"
# Использовать его вместо ad-hoc
codesign --force --deep --sign "Kadero Dev" KaderoAgent.app
```

Или стабильный ad-hoc с `--identifier`:
```bash
codesign --force --deep --sign - --identifier "ru.kadero.agent" KaderoAgent.app
```

### Исправление 5: Инструкция для пользователя

Если permission denied — показать osascript диалог с точной инструкцией:

```
"Кадеро не может начать запись.

Разрешение Screen Recording не выдано для этой версии приложения.

1. Откройте System Settings → Privacy & Security → Screen Recording
2. Удалите старую запись KaderoAgent (если есть)
3. Нажмите '+' и добавьте /Applications/KaderoAgent.app
4. Перезапустите агент

(Это нужно делать после каждого обновления, пока не будет настроен Developer ID signing)"
```

## Чеклист исправления

- [ ] Заменить `CGWindowListCopyWindowInfo` на `SCShareableContent` в `PermissionChecker`
- [ ] Избежать deadlock: `DispatchQueue.global().async { Task { ... } }` + `DispatchGroup`
- [ ] В `startRecording()` — детектить permission error и ставить `waiting_permission`
- [ ] Логировать `error` полностью: `localizedDescription` + `type(of:)` + raw `\(error)`
- [ ] `codesign --identifier "ru.kadero.agent"` в build-installer.sh
- [ ] Показать GUI-инструкцию при permission denied
- [ ] Тест: новая сборка → установка → без permission → ошибка корректная → дать permission → restart → запись работает

## Приоритет

**High** — без этого фикса агент бесполезен: PermissionChecker говорит "ОК", но запись не стартует.
