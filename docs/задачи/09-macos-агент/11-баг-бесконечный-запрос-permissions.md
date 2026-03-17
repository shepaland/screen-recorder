# Баг: Бесконечный запрос разрешений при наличии permission

## Симптом

Пользователь выдал Screen Recording permission в System Settings. Агент при запуске всё равно показывает "Ожидание разрешений" и бесконечно поллит, не начиная запись.

## Корневая причина

Три фактора в комбинации:

### 1. macOS кеширует TCC per-process

`SCShareableContent.excludingDesktopWindows()` проверяет TCC (Transparency, Consent and Control) базу **в момент первого вызова** и кеширует результат для текущего процесса. Если permission выдан ПОСЛЕ запуска процесса — процесс всё равно получает `false` до рестарта.

Текущий flow:
```
Запуск → hasScreenCapture = false → waitForPermissions() → polling каждые 10с
→ пользователь даёт permission в System Settings
→ SCShareableContent ВСЕГДА false (cached per-process)
→ агент никогда не видит permission → бесконечный цикл
```

### 2. exit(0) не работает без LaunchAgent

В `waitForPermissions()` при обнаружении permission планировался `exit(0)` → LaunchAgent рестартит → новый процесс видит permission. Но LaunchAgent больше не устанавливается автоматически (исправление дублей из фазы 8). Значит `exit(0)` = агент просто закрывается навсегда.

### 3. SCShareableContent deadlock в NSApplication context

`SCShareableContent` вызывается через `DispatchQueue.global().async { Task { ... } }` с семафором. В контексте `NSApplication.shared.run()` это может deadlock'иться или возвращать `false` из-за конфликта с AppKit event loop.

## Решение

### Подход: Не проверять permission до попытки записи

Вместо pre-check + polling — **попробовать запустить SCStream напрямую**. Если permission нет — SCStream.startCapture() бросит ошибку. Обработать её и показать сообщение.

```swift
func startRecording() async {
    // Убрать guard permissionChecker.hasScreenCapture
    // Просто попробовать стартовать:
    do {
        let sessionId = try await sessionManager.startSession()
        try await screenRecorder.startCapture(sessionId: sessionId)  // ← бросит ошибку если нет permission
        // Успех — permission есть
        agentState.status = "recording"
    } catch {
        // Определить: это permission error или другая ошибка
        if isPermissionError(error) {
            agentState.status = "waiting_permission"
            agentState.setError("Разрешение Screen Recording не выдано. Добавьте приложение в System Settings → Privacy & Security → Screen Recording и перезапустите.")
            // НЕ exit, НЕ бесконечный polling — просто ждать команды от пользователя
        } else {
            agentState.status = "error"
            agentState.setError(error.localizedDescription)
        }
    }
}

func isPermissionError(_ error: Error) -> Bool {
    let desc = "\(error)"
    return desc.isEmpty
        || desc.contains("отклонил")
        || desc.contains("denied")
        || desc.contains("not permitted")
        || desc.contains("tccd")
}
```

### Что убрать

1. **Убрать `waitForPermissions()`** — бесполезная функция, кеш per-process не обновляется
2. **Убрать pre-check `guard permissionChecker.hasScreenCapture`** в `startRecording()` — ненадёжен
3. **Убрать polling 10с** — бессмысленно, SCShareableContent кеширует per-process

### Что оставить

1. **`PermissionChecker.hasScreenCapture`** — использовать ТОЛЬКО в StatusWindow для информативного отображения (с `isActivelyRecording` override)
2. **`CGRequestScreenCaptureAccess()`** — вызывать при первом запуске для показа системного промпта
3. **Кнопку "Начать запись"** в меню — пользователь нажимает после выдачи permission

### Новый flow

```
Запуск → Регистрация → CGRequestScreenCaptureAccess() (промпт)
→ Heartbeat стартует, Activity tracking стартует
→ Если --record: попытка startRecording()
  → Успех: запись идёт
  → Permission error: статус "waiting_permission", сообщение в меню
    → Пользователь даёт permission в System Settings
    → Пользователь нажимает "Начать запись" в меню
    → startRecording() пробует снова (новый вызов SCStream)
    → На некоторых macOS: нужен перезапуск → показать сообщение "Перезапустите приложение"
```

### Обработка перезапуска

Если `startRecording()` после выдачи permission всё ещё падает (cached TCC):

```swift
// В startRecording, если permission error и пользователь явно нажал "Начать запись":
agentState.setError("Разрешение выдано, но требуется перезапуск. Нажмите 'Выход' и откройте приложение заново.")
```

## Чеклист

- [ ] Убрать `waitForPermissions()` из `run()`
- [ ] Убрать `guard permissionChecker.hasScreenCapture` из `startRecording()`
- [ ] В `startRecording()`: ловить permission error из `SCStream.startCapture()`
- [ ] При permission error: статус `waiting_permission`, понятное сообщение
- [ ] НЕ exit(0), НЕ polling — ждать действия пользователя
- [ ] Кнопка "Начать запись" в меню доступна при `waiting_permission`
- [ ] При повторной ошибке после выдачи permission: сообщение "Перезапустите"
- [ ] Heartbeat и Activity tracking работают независимо от permission
- [ ] StatusWindow показывает permission status через `isActivelyRecording` override

## Приоритет

**Critical** — без этого фикса агент неработоспособен на любой новой установке.
