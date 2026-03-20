# Задача: Немедленный старт записи и телеметрии после установки

**Приоритет:** High
**Тип:** Task
**Компонент:** windows-agent-csharp / HeartbeatService, AgentService

---

## Текущее поведение

После установки агент проходит цикл:
1. Аутентификация (device-login) → `Configuring`
2. Переход в `Online`
3. **Ожидание heartbeat** с `device_settings` от сервера
4. Только после получения `device_settings` с `ConfigReceivedFromServer=true` → auto-start записи

**Проблема:** Если сервер не возвращает `device_settings` в heartbeat response (или heartbeat не проходит), агент навсегда остаётся в `Online` и не начинает запись. Первые минуты после установки теряются — нет ни записи, ни телеметрии.

---

## Требуемое поведение

Агент должен **немедленно** начать запись и сбор телеметрии после установки и аутентификации, используя **дефолтные параметры**:

| Параметр | Значение | Поле в AgentConfig |
|----------|----------|-------------------|
| Длительность чанка | 60 секунд | `SegmentDurationSec = 60` |
| Макс. длительность сессии | 60 минут | `SessionMaxDurationMin = 60` |
| Разрешение | 720p | `Resolution = "1280x720"` |
| Качество | Низкое | `Quality = "low"` |
| FPS | 1 | `CaptureFps = 1` |

Когда сервер пришлёт `device_settings` через heartbeat — агент **перейдёт** на серверные параметры (hot-restart если FPS/resolution изменились).

---

## Анализ кода

### Что блокирует немедленный старт

**Файл:** `Command/HeartbeatService.cs:144`
```csharp
if (serverCfg is { ConfigReceivedFromServer: true, AutoStart: true, RecordingEnabled: true })
```

Условие `ConfigReceivedFromServer: true` — главный гейт. Без него auto-start невозможен.

**Файл:** `Service/AgentService.cs:111-114`
```csharp
// Auto-start: ALWAYS wait for first heartbeat to get fresh config from server.
// Never trust saved AutoStart — it may be stale. HeartbeatService will handle
// auto-start with exponential backoff after receiving fresh device_settings.
```

Комментарий объясняет текущую логику — ждать сервер. Нужно изменить.

### Что уже готово (дефолты совпадают с требованиями)

**Файл:** `Configuration/AgentConfig.cs`
```csharp
public int SegmentDurationSec { get; set; } = 60;     // ✅ 60 секунд
public int CaptureFps { get; set; } = 1;               // ✅ 1 FPS
public string Quality { get; set; } = "low";           // ✅ низкое качество
public string Resolution { get; set; } = "1280x720";   // ✅ 720p
public int SessionMaxDurationMin { get; set; } = 60;    // ✅ 60 минут
public bool AutoStart { get; set; } = true;             // ✅ auto-start
```

Все дефолты в `AgentConfig` уже соответствуют требованиям. Менять не нужно.

### Телеметрия (сбор focus intervals, audit events)

Телеметрия (SessionWatcher, ProcessWatcher, FocusIntervalSink, InputEventSink) уже запускается сразу после аутентификации — она не гейтится `ConfigReceivedFromServer`. Headless collector запускается из SessionWatcher при обнаружении активной сессии. **Телеметрия уже работает немедленно.**

---

## Решение

### Вариант: Убрать гейт `ConfigReceivedFromServer` из auto-start

Изменить условие в `HeartbeatService.cs:144`:

**Было:**
```csharp
if (serverCfg is { ConfigReceivedFromServer: true, AutoStart: true, RecordingEnabled: true })
```

**Стало:**
```csharp
// Auto-start uses local defaults until server provides config.
// ConfigReceivedFromServer no longer blocks auto-start.
var autoStart = serverCfg?.AutoStart ?? _config.Value.AutoStart;
var recordingEnabled = serverCfg?.RecordingEnabled ?? true;
if (autoStart && recordingEnabled)
```

### Логика после изменения

1. **Установка** → аутентификация → `Online`
2. **Первый heartbeat-цикл** (через 30 сек) → `serverCfg` ещё null или без `ConfigReceivedFromServer` → используются дефолты из `AgentConfig` → **запись стартует немедленно** с 60s/60min/720p/low/1fps
3. **Heartbeat с `device_settings`** → `ServerConfig` обновляется, `ConfigReceivedFromServer=true` → если FPS/resolution изменились, hot-restart с серверными параметрами
4. **`recording_enabled=false` от сервера** → запись останавливается (существующая логика)

### Также убрать блокировку в SessionWatcher

**Файл:** `Audit/SessionWatcher.cs` — `ResumeRecordingAsync` и `HandleSessionLogonAsync` уже используют `ConfigReceivedFromServer` как гейт для возобновления записи после unlock/logon.

**Файл:** `Command/CommandHandler.cs:100-105`
```csharp
if (!configFromServer)
{
    _logger.LogInformation("ServerConfig not confirmed by server, not resuming after unlock. " +
        "Heartbeat will deliver config and trigger auto-start if needed.");
    return;
}
```

Нужно убрать этот гейт — если конфиг от сервера не получен, использовать дефолты.

---

## Затронутые файлы

| Файл | Изменение |
|------|-----------|
| `Command/HeartbeatService.cs:144` | Убрать `ConfigReceivedFromServer` из условия auto-start |
| `Command/CommandHandler.cs:92-105` | Убрать `configFromServer` гейт в `ResumeRecordingAsync` |
| `Audit/SessionWatcher.cs:240-246` | Убрать `configFromServer` гейт в обработчике Logon |
| `Service/AgentService.cs:111-114` | Обновить комментарий |

---

## Что НЕ менять

- **AgentConfig дефолты** — уже совпадают с требованиями (60s/60min/720p/low/1fps)
- **Hot-restart при изменении FPS** — уже работает в HeartbeatService
- **recording_enabled гейт** — оставить, администратор должен иметь возможность запретить запись
- **Телеметрия** — уже стартует немедленно

---

## Тестирование

1. **Чистая установка**: установить агент → через 30-60 сек должна начаться запись с дефолтными параметрами (без ожидания device_settings)
2. **Сервер недоступен**: остановить heartbeat endpoint → агент всё равно пишет с дефолтами
3. **Переход на серверные настройки**: сервер присылает `capture_fps=2` → hot-restart, запись с 2 FPS
4. **Lock/Unlock**: заблокировать → idle. Разблокировать → запись возобновляется (даже если ConfigReceivedFromServer=false)
5. **recording_enabled=false**: сервер запрещает запись → агент останавливает (не обходит)
