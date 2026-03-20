# Задача: Устранить дублирование сессий при старте записи

**Приоритет:** High
**Тип:** Bug
**Компонент:** windows-agent-csharp / AgentService, HeartbeatService, CommandHandler

---

## Проблема

При старте агента v2026.3.21.1 создаются **две сессии записи**:

```
00:14:40.535  online
00:14:40.563  Starting recording immediately    ← AgentService (немедленный старт)
00:14:40.564  Auto-starting recording...
00:14:43.115  Auto-starting recording...        ← HeartbeatService (параллельный цикл)
00:14:43.568  Recording started: session=c29b6472...  ← сессия 1
00:14:45.699  Recording started: session=15059765...  ← сессия 2 (ДУБЛЬ)
```

### Корневая причина: гонка между AgentService и HeartbeatService

1. `AgentService.ExecuteAsync()` вызывает `AutoStartRecordingAsync()` → `StartRecording()` → FFmpeg запускается, `_isRecording = true` ставится на строке 125 в `ScreenCaptureManager.Start()`
2. **Одновременно** `HeartbeatService` уже запущен как `BackgroundService` и его первый heartbeat-цикл тоже вызывает `AutoStartRecordingAsync()`
3. Между моментом вызова `AutoStartRecordingAsync` из AgentService и моментом, когда `_isRecording` станет `true`, HeartbeatService успевает проверить `_captureManager.IsRecording` (ещё `false`) и тоже войти в `StartRecording()`

### Почему существующие проверки не спасают

- `AutoStartRecordingAsync:201` — `if (_captureManager.IsRecording) return` — проверяет `_isRecording`, но FFmpeg ещё не запущен (Start() ещё не дошёл до строки 125)
- `StartRecording:270` — `if (_captureManager.IsRecording) return` — та же проблема
- `ScreenCaptureManager.Start:43` — `if (_isRecording) return true` — та же проблема
- Все три проверки читают `_isRecording` до того, как первый вызов успел его установить

### Последствия

- Две параллельных сессии FFmpeg на одном экране
- Двойной расход CPU, диска, трафика
- Два набора сегментов загружаются на сервер
- Возможные конфликты при записи в один и тот же desktop

---

## Решение

### Вариант: Добавить lock в StartRecording

Добавить `SemaphoreSlim` в `CommandHandler`, который гарантирует, что `StartRecording` не может выполняться параллельно из разных потоков.

**Файл:** `Command/CommandHandler.cs`

```csharp
private readonly SemaphoreSlim _startLock = new(1, 1);

private async Task StartRecording(string baseUrl, CancellationToken ct)
{
    if (_captureManager.IsRecording) return;

    if (!await _startLock.WaitAsync(0, ct))
    {
        _logger.LogDebug("StartRecording already in progress, skipping");
        return;
    }
    try
    {
        if (_captureManager.IsRecording) return; // double-check under lock

        // ... существующая логика StartRecording ...
    }
    finally
    {
        _startLock.Release();
    }
}
```

`WaitAsync(0)` — неблокирующий try-acquire. Если lock занят — значит другой поток уже стартует запись, и текущий вызов просто выходит.

---

## Затронутые файлы

| Файл | Изменение |
|------|-----------|
| `Command/CommandHandler.cs` | Добавить `_startLock` SemaphoreSlim, обернуть `StartRecording` |

Один файл, ~10 строк.

---

## Тестирование

1. **Старт агента**: в логах должна быть ровно одна `Recording started` после установки
2. **HeartbeatService**: второй `Auto-starting` должен видеть `Already recording, skip auto-start` или `StartRecording already in progress`
3. **Lock/Unlock**: после разблокировки — одна сессия, не две
4. **Session rotation**: при ротации (60 мин) — корректная смена, не дублирование
