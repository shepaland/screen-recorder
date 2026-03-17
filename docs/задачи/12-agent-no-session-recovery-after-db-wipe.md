# 12. Агент не создаёт новую сессию после удаления данных из БД

## Описание дефекта

После очистки БД (DELETE FROM recording_sessions, segments) агент AIR911D продолжает шлёть heartbeat со статусом `recording` и presign-запросы для удалённой сессии `6d305e4e` / `260c9b4a`. Сервер возвращает 404 (`Recording session not found`), но агент **не создаёт новую сессию** — вместо этого бесконечно ретраит presign каждую минуту.

## Логи

```
15:34:03 WARN  Resource not found: Recording session not found: 260c9b4a-...
15:35:03 WARN  Resource not found: Recording session not found: 260c9b4a-...
...повторяется каждую минуту...
15:39:03 WARN  Resource not found: Recording session not found: 6d305e4e-...
15:40:03 WARN  Resource not found: Recording session not found: 6d305e4e-...
...переключился на другую удалённую сессию, тот же результат...
```

macOS-агент (c308f10c, hnba.local) при этом **создаёт** новые сессии нормально (7 сессий за 5 минут).

## Корневая причина

Windows-агент хранит `session_id` в памяти (`SessionManager._currentSessionId`). Когда presign/confirm получает 404, агент:

1. **Не инвалидирует текущую сессию** — `_currentSessionId` остаётся заполненным
2. **Не пытается создать новую сессию** — `SegmentUploader` продолжает шлёть presign с тем же session_id
3. **Retry без backoff** — каждую минуту (при каждом новом сегменте) повторяет presign с тем же результатом

### Где проблема в коде

**SegmentUploader.cs** — вызывает presign, получает 404, но не обрабатывает этот случай как сигнал для пересоздания сессии.

**SessionManager.cs** — нет метода для инвалидации текущей сессии по 404 ошибке.

**RecordingPipeline / RecordingOrchestrator** (если есть) — нет механизма "если сессия исчезла, пересоздать".

## Ожидаемое поведение

При получении 404 на presign/confirm для текущей сессии:
1. Агент инвалидирует `_currentSessionId = null`
2. Агент создаёт новую сессию (`POST /sessions`)
3. Агент перенаправляет pending-сегменты в новую сессию
4. Запись продолжается без ручного вмешательства

## Связанные задачи

Ранее была реализована обработка 404 при session recovery (`fix/agent-session-recovery` — коммит `be8ee1a`), но та логика касалась pending-сегментов от **старых** сессий. Текущий дефект — агент не умеет recovery для **текущей активной** сессии при её внезапном исчезновении.

## План исправления

### Вариант A: Обработка 404 в SegmentUploader (минимальный)

```csharp
// SegmentUploader.cs — в методе UploadSegmentAsync
catch (HttpRequestException ex) when (ex.StatusCode == HttpStatusCode.NotFound)
{
    _logger.LogWarning("Session {SessionId} not found (404). Resetting session...", sessionId);
    _sessionManager.InvalidateSession();
    // Сегмент будет повторно отправлен с новой сессией при следующей итерации
    return false;
}
```

```csharp
// SessionManager.cs
public void InvalidateSession()
{
    _logger.LogWarning("Session invalidated: {SessionId}", _currentSessionId);
    _currentSessionId = null;
}
```

```csharp
// RecordingOrchestrator или UploadQueue — при _currentSessionId == null:
if (_sessionManager.CurrentSessionId == null)
{
    var newSessionId = await _sessionManager.StartSessionAsync(fps, resolution, ct);
    // Пересоздать сессию и продолжить
}
```

### Вариант B: Периодическая проверка сессии (robust)

- При каждом heartbeat проверять, что `currentSessionId` всё ещё exists на сервере
- Если 404 — пересоздать сессию

### Рекомендация

**Вариант A** — проще, решает конкретный сценарий. Вариант B избыточен — 404 при presign достаточный сигнал.

## Файлы для изменений

| Файл | Изменение |
|------|-----------|
| `windows-agent-csharp/src/KaderoAgent/Upload/SessionManager.cs` | Добавить `InvalidateSession()` |
| `windows-agent-csharp/src/KaderoAgent/Upload/SegmentUploader.cs` | Обработка 404 → InvalidateSession() |
| `windows-agent-csharp/src/KaderoAgent/Upload/UploadQueue.cs` | При null session — пересоздать сессию перед upload |

---

## Дефект 2: Агент молча теряет сегменты — UI не показывает проблему

### Описание

При получении 404 от сервера:
- **Очередь (QueuedCount)** — не копится, остаётся на 0. Оператор видит `Очередь: 0` и думает, что всё работает.
- **Статус записи** — остаётся "Включено" (зелёный индикатор). Агент продолжает захват экрана и создаёт новые mp4-файлы.
- **Нет ошибки** — в окне статуса нет сообщения об ошибке. Поле `LastError` не устанавливается.
- **Индикатор подключения** — "Подключен" (зелёный). Heartbeat работает, авторизация валидна.

### Корневая причина

**SegmentUploader.cs (строки 94-100):**
```csharp
catch (HttpRequestException ex) when (ex.StatusCode == HttpStatusCode.NotFound)
{
    _logger.LogWarning("Session not found (404) for segment {Seq}..., discarding");
    return true;  // ← ДИСКАРДИТ СЕГМЕНТ КАК УСПЕШНЫЙ!
}
```

При 404 метод возвращает `true` — UploadQueue считает upload успешным, удаляет файл сегмента, и `QueuedCount` уменьшается. Сегмент потерян безвозвратно.

**AgentStatusProvider.cs:**
- `RecordingStatus` = `"recording"` — зависит от `AgentState`, а не от реального состояния upload.
- `SegmentsQueued` = `_uploadQueue.QueuedCount` = 0 (сегменты дискардятся моментально).
- `LastError` = `null` — SegmentUploader не вызывает `_statusProvider.SetLastError()`.
- Нет счётчика потерянных/дискарднутых сегментов.

**StatusWindow.cs:**
- Показывает "Включено" + зелёный индикатор — состояние `recording` из AgentState.
- `QueuedCount` = 0 — нет визуального сигнала о проблеме.
- `LastError` = null — нет текста ошибки.

### Что видит оператор

| Элемент UI | Значение | Реальность |
|-----------|----------|-----------|
| Запись | Включено (зелёный) | Захват идёт, но файлы удаляются |
| Подключение | Подключен (зелёный) | Heartbeat работает |
| Очередь | 0 | Сегменты дискардятся, не копятся |
| Ошибка | — | Нет ошибки, хотя данные теряются |

### Ожидаемое поведение

| Элемент UI | Ожидаемое значение |
|-----------|-------------------|
| Запись | **Ошибка записи** (красный) или **Переподключение** (оранжевый) |
| Подключение | Подключен (зелёный) — heartbeat действительно работает |
| Очередь | N (копится) или показывать "потерянных: M" |
| Ошибка | "Сессия не найдена на сервере. Пересоздание..." |

---

## Дефект 3: В UI отображается только очередь сегментов — нет очередей событий

### Описание

В окне статуса агента показан единственный счётчик `Очередь: N` — это `SegmentsQueued` (количество видеосегментов в Channel). Но агент накапливает **четыре** независимые очереди данных, три из которых **невидимы** оператору:

| Очередь | Класс | Свойство | Тип данных | В UI |
|---------|-------|----------|-----------|------|
| Видеосегменты | `UploadQueue` | `QueuedCount` | mp4-файлы (60с каждый) | Отображается |
| Аудит-события | `AuditEventSink` | `QueuedCount` | SESSION_LOCK/UNLOCK, LOGON/LOGOFF, PROCESS_START/STOP | Передаётся в AgentStatus, но **НЕ отображается** |
| Focus-интервалы | `FocusIntervalSink` | `QueuedCount` | Переключение окон (app_name, window_title, duration) | **НЕ передаётся** в AgentStatus, не отображается |
| Input-события | `InputEventSink` | `QueuedCount` | Клики, скроллы, нажатия клавиш (агрегированные) | **НЕ передаётся** в AgentStatus, не отображается |

### Что это значит для оператора

Когда сервер недоступен или сессия потеряна:
- **Видео** — очередь сегментов = 0 (дискардятся при 404, Дефект 1+2)
- **Поведение** — аудит-события, focus-интервалы, input-события копятся в in-memory очередях и SQLite, но оператор не видит сколько
- При восстановлении связи — накопленные события отправятся (если сервис не перезапущен), но нет возможности понять был ли пробел

### Ожидаемое поведение UI

Секция "Очереди" в окне статуса:

```
┌────────────────────────────────────────┐
│ ОЧЕРЕДИ ПЕРЕДАЧИ                       │
│                                        │
│ Видеосегменты    ●  3 ожидают          │
│ Аудит-события    ●  47 ожидают         │
│ Focus-интервалы  ●  128 ожидают        │
│ Input-события    ●  85 ожидают         │
│                                        │
│ ● зелёный = 0, ● жёлтый = 1-50,       │
│ ● красный = 50+                        │
└────────────────────────────────────────┘
```

Индикатор цветом: зелёный (0 — всё передано), жёлтый (1-50 — небольшое отставание), красный (50+ — проблема с передачей).

---

## Полный план исправления

### Шаг 1: SessionManager — добавить InvalidateSession() и флаг ошибки

```csharp
// SessionManager.cs
private int _consecutiveSessionErrors;

public void InvalidateSession()
{
    _logger.LogWarning("Session invalidated: {SessionId}. Will create new on next segment.", _currentSessionId);
    _currentSessionId = null;
    _consecutiveSessionErrors++;
}

public bool HasSessionError => _consecutiveSessionErrors > 0;

public void ClearSessionError() => _consecutiveSessionErrors = 0;
```

### Шаг 2: SegmentUploader — при 404 инвалидировать сессию, не дискардить сегмент

```csharp
// SegmentUploader.cs — строки 94-100, ЗАМЕНИТЬ:
catch (HttpRequestException ex) when (ex.StatusCode == HttpStatusCode.NotFound)
{
    _logger.LogWarning("Session {Session} not found (404) for segment {Seq}. Invalidating session.",
        sessionId, sequenceNum);
    _sessionManager.InvalidateSession();
    return false;  // ← НЕ дискардить! Вернуть false → сегмент сохранится в pending
}
```

### Шаг 3: UploadQueue — при null session пересоздать перед upload

```csharp
// UploadQueue.cs — в ProcessLoop, перед вызовом UploadSegmentAsync:
private async Task ProcessLoop(string serverUrl, CancellationToken ct)
{
    await foreach (var segment in _channel.Reader.ReadAllAsync(ct))
    {
        // If current session was invalidated, create a new one
        if (_sessionManager.CurrentSessionId == null)
        {
            try
            {
                _logger.LogInformation("No active session. Creating new session...");
                await _sessionManager.StartSessionAsync(ct: ct);
                _logger.LogInformation("New session created: {SessionId}", _sessionManager.CurrentSessionId);
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "Failed to create new session. Segment queued for retry.");
                _db.SavePendingSegment(segment);
                await Task.Delay(10_000, ct);
                continue;
            }
        }

        // Override segment's session_id with current session
        var effectiveSessionId = _sessionManager.CurrentSessionId ?? segment.SessionId;

        var success = await _uploader.UploadSegmentAsync(
            segment.FilePath, effectiveSessionId, segment.SequenceNum, serverUrl, ct);
        // ... rest of existing logic
    }
}
```

### Шаг 4: AgentStatusProvider — добавить upload error state

```csharp
// AgentStatusProvider.cs — в GetCurrentStatus():
// После существующих полей:
status.UploadError = _sessionManager.HasSessionError;
status.UploadErrorMessage = _sessionManager.HasSessionError
    ? "Сессия потеряна, пересоздание..."
    : null;
```

### Шаг 5: AgentStatus — добавить поля для upload error

```csharp
// В AgentStatus class (Ipc/PipeProtocol.cs или отдельный файл):
public bool UploadError { get; set; }
public string? UploadErrorMessage { get; set; }
public int SegmentsLost { get; set; }  // Счётчик дискарднутых (для отладки)
```

### Шаг 6: StatusWindow — показать проблему в UI

```csharp
// StatusWindow.cs — в UpdateUI():
// После блока Recording status:
if (status.UploadError)
{
    _recordingStatusLabel.Text = status.UploadErrorMessage ?? "Ошибка загрузки";
    _recordingIndicator.BackColor = GlassHelper.StatusOrange;
}
```

### Шаг 7: TrayIcon — изменить иконку при ошибке upload

```csharp
// TrayIcons.cs или TrayApplication.cs:
// При upload error менять иконку трея на оранжевую/жёлтую
// чтобы оператор видел проблему даже с закрытым окном статуса
```

---

### Шаг 8: AgentStatusProvider — добавить все очереди

```csharp
// AgentStatusProvider.cs — добавить зависимости:
private readonly FocusIntervalSink _focusSink;
private readonly InputEventSink _inputSink;

// В GetCurrentStatus():
status.SegmentsQueued = _uploadQueue.QueuedCount;
status.AuditEventsQueued = _auditSink.QueuedCount;
status.FocusIntervalsQueued = _focusSink.QueuedCount;    // NEW
status.InputEventsQueued = _inputSink.QueuedCount;        // NEW
```

### Шаг 9: AgentStatus (PipeProtocol) — добавить поля для всех очередей

```csharp
// PipeProtocol.cs — AgentStatus class:
public int SegmentsQueued { get; set; }         // уже есть
public int AuditEventsQueued { get; set; }      // уже есть, но не показывается
public int FocusIntervalsQueued { get; set; }   // NEW
public int InputEventsQueued { get; set; }      // NEW
```

### Шаг 10: StatusWindow — секция "Очереди передачи"

Заменить одиночный `_queueValue` на секцию из четырёх строк:

```csharp
// StatusWindow.cs — в InitializeComponent():
// Секция "ОЧЕРЕДИ ПЕРЕДАЧИ"
var queueLabel = GlassHelper.CreateHeaderLabel("ОЧЕРЕДИ ПЕРЕДАЧИ", left, y);
Controls.Add(queueLabel);
y += 24;

_segmentsQueueIndicator = CreateIndicator(left, y);
_segmentsQueueLabel = GlassHelper.CreateLabel("Видеосегменты: 0", left + 16, y, 250);
y += 20;

_auditQueueIndicator = CreateIndicator(left, y);
_auditQueueLabel = GlassHelper.CreateLabel("Аудит-события: 0", left + 16, y, 250);
y += 20;

_focusQueueIndicator = CreateIndicator(left, y);
_focusQueueLabel = GlassHelper.CreateLabel("Focus-интервалы: 0", left + 16, y, 250);
y += 20;

_inputQueueIndicator = CreateIndicator(left, y);
_inputQueueLabel = GlassHelper.CreateLabel("Input-события: 0", left + 16, y, 250);

// В UpdateUI():
UpdateQueueRow(_segmentsQueueIndicator, _segmentsQueueLabel, "Видеосегменты", status.SegmentsQueued);
UpdateQueueRow(_auditQueueIndicator, _auditQueueLabel, "Аудит-события", status.AuditEventsQueued);
UpdateQueueRow(_focusQueueIndicator, _focusQueueLabel, "Focus-интервалы", status.FocusIntervalsQueued);
UpdateQueueRow(_inputQueueIndicator, _inputQueueLabel, "Input-события", status.InputEventsQueued);

private void UpdateQueueRow(Panel indicator, Label label, string name, int count)
{
    label.Text = $"{name}: {count}";
    indicator.BackColor = count == 0 ? GlassHelper.StatusGreen
                        : count < 50 ? GlassHelper.StatusYellow
                        : GlassHelper.StatusRed;
    indicator.Invalidate();
}
```

---

## Файлы для изменений (полный список)

### Дефект 1+2: Session recovery + silent data loss

| Файл | Изменение | Приоритет |
|------|-----------|-----------|
| `Upload/SessionManager.cs` | `InvalidateSession()`, `HasSessionError`, `ClearSessionError()` | Critical |
| `Upload/SegmentUploader.cs` | При 404 → `InvalidateSession()` + `return false` | Critical |
| `Upload/UploadQueue.cs` | При null session → `StartSessionAsync()` перед upload | Critical |
| `Service/AgentStatusProvider.cs` | Добавить `UploadError` / `UploadErrorMessage` | Medium |
| `Ipc/PipeProtocol.cs` | Добавить `UploadError`, `UploadErrorMessage` в `AgentStatus` | Medium |
| `Tray/StatusWindow.cs` | Показать ошибку upload оранжевым в UI | Medium |
| `Tray/TrayApplication.cs` | Иконка трея при upload error | Low |

### Дефект 3: Очереди всех типов данных в UI

| Файл | Изменение | Приоритет |
|------|-----------|-----------|
| `Service/AgentStatusProvider.cs` | Добавить `FocusIntervalsQueued`, `InputEventsQueued` + inject `FocusIntervalSink`, `InputEventSink` | Medium |
| `Ipc/PipeProtocol.cs` | Добавить `FocusIntervalsQueued`, `InputEventsQueued` в `AgentStatus` | Medium |
| `Tray/StatusWindow.cs` | Заменить одиночный `Очередь: N` на секцию из 4 строк с цветовыми индикаторами | Medium |

## Workaround (до исправления)

Перезапустить Windows-сервис агента:
```
sc stop KaderoAgent && timeout /t 3 && sc start KaderoAgent
```
При старте агент создаст новую сессию.
