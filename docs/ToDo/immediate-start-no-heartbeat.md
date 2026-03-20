# Задача: Старт записи сразу после аутентификации (без ожидания heartbeat)

**Приоритет:** High
**Тип:** Task
**Компонент:** windows-agent-csharp / AgentService

---

## Текущее поведение (v2026.3.20.3)

```
Установка → Аутентификация → Online → [ждём heartbeat 30 сек] → Auto-start → Recording
```

Auto-start по-прежнему привязан к циклу `HeartbeatService` (каждые 30 сек). Хотя гейт `ConfigReceivedFromServer` убран, запись не начнётся, пока не отработает первый heartbeat-цикл. Если сервер недоступен — heartbeat падает, auto-start не срабатывает.

## Требуемое поведение

```
Установка → Аутентификация → [немедленно] → Recording + Телеметрия
```

Агент должен начать запись и сбор телеметрии **сразу после аутентификации**, не зависимо от:
- Доступности сервера
- Ответа heartbeat
- Получения device_settings

Параметры записи по умолчанию: 60s чанк, 60 мин сессия, 720p, low quality, 1 FPS.

---

## Анализ: что нужно изменить

### 1. `AgentService.ExecuteAsync()` — добавить немедленный auto-start

**Файл:** `Service/AgentService.cs:105`

После `SetState(AgentState.Online)` и запуска headless collector — вызвать `_commandHandler.AutoStartRecordingAsync()` напрямую, не дожидаясь heartbeat.

**Было:**
```csharp
SetState(AgentState.Online);
// ...
_logger.LogInformation("Recording will auto-start on first heartbeat cycle with local defaults");
```

**Стало:**
```csharp
SetState(AgentState.Online);
// ...
// Immediate auto-start with local defaults — no server dependency
await _commandHandler.AutoStartRecordingAsync(baseUrl, ct);
if (_captureManager.IsRecording)
    SetState(AgentState.Recording);
```

### 2. Учесть состояние пользовательской сессии

Auto-start нужен ТОЛЬКО если пользователь залогинен (сессия активна). Если сессия заблокирована или нет пользователя (`AwaitingUser`) — НЕ стартовать, дождаться unlock/logon (SessionWatcher обработает).

```csharp
if (_sessionWatcher == null || _sessionWatcher.IsSessionActive)
{
    await _commandHandler.AutoStartRecordingAsync(baseUrl, ct);
    if (_captureManager.IsRecording)
        SetState(AgentState.Recording);
}
```

### 3. HeartbeatService auto-start — оставить как fallback

Логику auto-start в HeartbeatService **не убирать** — она нужна как fallback для:
- Восстановления после FFmpeg crash
- Retry при `DesktopUnavailable`
- Повторного старта после unlock (если SessionWatcher не справился)
- Hot-restart при изменении параметров от сервера

### 4. Телеметрия

Headless collector уже запускается сразу после аутентификации (строка 117-121 в AgentService). **Менять не нужно.**

---

## Затронутые файлы

| Файл | Изменение |
|------|-----------|
| `Service/AgentService.cs` | Добавить вызов `AutoStartRecordingAsync` после аутентификации + headless collector |

Один файл, ~5 строк кода.

---

## Тестирование

1. **Чистая установка**: запись стартует за 2-3 секунды после аутентификации (не 30+)
2. **Сервер недоступен**: выключить control-plane → установить агент → запись всё равно стартует
3. **Экран заблокирован при старте**: агент в `AwaitingUser`, запись НЕ стартует. Разблокировать → запись стартует.
4. **Hot-restart**: после получения device_settings с другим FPS — перезапуск записи с новыми параметрами
