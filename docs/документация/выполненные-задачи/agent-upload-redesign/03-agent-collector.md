# Фаза 3: Агент — Collector refactor (Sink'и → SQLite)

> **Цель:** Все источники данных пишут в SQLite вместо RAM. BackgroundService flush-loops удаляются из sink'ов. Старая сетевая отправка (UploadQueue, Sinks) **пока сохраняется** как fallback — удалится в Фазе 4.
>
> **Зависит от:** Фаза 2 (SQLite v2)
> **Блокирует:** Фазу 4 (DataSyncService)

---

## Стратегия: Dual-Write → Cutover

На этой фазе sink'и пишут **и в SQLite (NEW), и в старые очереди** (ConcurrentQueue / UploadQueue). Это позволяет:
- Проверить что SQLite-запись работает
- Не ломать текущую отправку (fallback на старый путь)
- В Фазе 4 — удалить старый путь, оставить только SQLite

---

## 3.1 `FocusIntervalSink` — dual-write

**Файл:** `Audit/FocusIntervalSink.cs`

**Текущее:**
```csharp
public void Enqueue(FocusInterval interval)
{
    _queue.Enqueue(interval);  // ConcurrentQueue, RAM only
}

// BackgroundService: flush каждые 30s → POST /focus-intervals
```

**Новое:**
```csharp
public void Enqueue(FocusInterval interval)
{
    // 1. НОВОЕ: persist в SQLite
    try
    {
        var payload = JsonSerializer.Serialize(interval, _jsonOptions);
        _db.InsertActivity(
            eventId: interval.Id,
            dataType: "FOCUS_INTERVAL",
            sessionId: interval.SessionId,
            payload: payload
        );
    }
    catch (Exception ex)
    {
        _logger.LogWarning(ex, "Failed to persist focus interval to SQLite, falling back to RAM");
    }

    // 2. СТАРОЕ: по-прежнему в RAM-очередь (пока не удалено)
    _queue.Enqueue(interval);
}
```

**BackgroundService flush-loop — НЕ УДАЛЯТЬ на этой фазе.** Он продолжает работать как раньше. В Фазе 4 его заменит DataSyncService.

**Проверка:**
- [ ] Focus interval записывается в `pending_activity` с `status=NEW`
- [ ] Старая отправка через POST /focus-intervals продолжает работать
- [ ] При ошибке SQLite — данные не теряются (fallback на RAM)

---

## 3.2 `InputEventSink` — dual-write

**Файл:** `Audit/InputEventSink.cs`

**Текущее:**
```csharp
public void HandleInputEvents(List<InputEvent> events)
{
    foreach (var evt in events)
    {
        switch (evt.EventType)
        {
            case "mouse_click": _mouseClicks.Enqueue(evt); break;
            case "keyboard_metric": _keyboardMetrics.Enqueue(evt); break;
            case "scroll": _scrollEvents.Enqueue(evt); break;
            case "clipboard": _clipboardEvents.Enqueue(evt); break;
        }
    }
}
```

**Новое:**
```csharp
public void HandleInputEvents(List<InputEvent> events)
{
    foreach (var evt in events)
    {
        // 1. НОВОЕ: persist в SQLite
        try
        {
            string dataType = evt.EventType switch
            {
                "mouse_click" => "MOUSE_CLICK",
                "keyboard_metric" => "KEYBOARD_METRIC",
                "scroll" => "SCROLL",
                "clipboard" => "CLIPBOARD",
                _ => "MOUSE_CLICK"
            };

            // Для clipboard: добавить content_hash
            if (evt.EventType == "clipboard" && evt.ClipboardContent != null)
            {
                evt.ContentHash = ComputeSha256(evt.ClipboardContent);
            }

            var payload = JsonSerializer.Serialize(evt, _jsonOptions);
            _db.InsertActivity(
                eventId: evt.Id,
                dataType: dataType,
                sessionId: evt.SessionId,
                payload: payload
            );
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Failed to persist input event to SQLite");
        }

        // 2. СТАРОЕ: по-прежнему в RAM-очереди
        switch (evt.EventType)
        {
            case "mouse_click": _mouseClicks.Enqueue(evt); break;
            // ...
        }
    }
}

private static string ComputeSha256(byte[] content)
{
    using var sha = SHA256.Create();
    var hash = sha.ComputeHash(content);
    return Convert.ToHexString(hash).ToLowerInvariant();
}
```

**Clipboard content_hash:**
- Вычислять SHA-256 от содержимого буфера обмена
- Для `text` — hash от UTF-8 bytes
- Для `image` — hash от raw bitmap bytes
- Для `files` — hash от конкатенации путей файлов
- Хранить в payload JSON как `content_hash`

**Проверка:**
- [ ] Mouse clicks → `pending_activity` с `data_type=MOUSE_CLICK`
- [ ] Keyboard metrics → `data_type=KEYBOARD_METRIC`
- [ ] Scroll → `data_type=SCROLL`
- [ ] Clipboard → `data_type=CLIPBOARD` + `content_hash` в payload
- [ ] Старая отправка продолжает работать

---

## 3.3 `AuditEventSink` — dual-write

**Файл:** `Audit/AuditEventSink.cs`

**Текущее:**
```csharp
public void Publish(AuditEvent evt)
{
    _db.SaveAuditEvent(evt);      // Старая таблица audit_events
    _queue.Enqueue(evt);          // RAM очередь для отправки
}
```

**Новое:**
```csharp
public void Publish(AuditEvent evt)
{
    // 1. НОВОЕ: persist в pending_activity
    try
    {
        var payload = JsonSerializer.Serialize(new
        {
            event_type = evt.EventType,
            event_ts = evt.EventTs,
            session_id = evt.SessionId,
            details = evt.Details
        }, _jsonOptions);

        _db.InsertActivity(
            eventId: evt.Id,
            dataType: "AUDIT_EVENT",
            sessionId: evt.SessionId,
            payload: payload
        );
    }
    catch (Exception ex)
    {
        _logger.LogWarning(ex, "Failed to persist audit event to pending_activity");
    }

    // 2. СТАРОЕ: по-прежнему в RAM-очередь (НЕ в старую audit_events таблицу — она уже мигрирована)
    _queue.Enqueue(evt);
}
```

> Старая таблица `audit_events` уже удалена в Фазе 2 (миграция). Поэтому `_db.SaveAuditEvent()` больше не вызывается.

**Проверка:**
- [ ] Audit events → `pending_activity` с `data_type=AUDIT_EVENT`
- [ ] Старая отправка через POST /audit-events продолжает работать (из RAM-очереди)
- [ ] `SaveAuditEvent()` больше не вызывается

---

## 3.4 `WatchSegments` (CommandHandler) — dual-write

**Файл:** `Command/CommandHandler.cs` — метод `WatchSegments`

**Текущее:**
```csharp
await _uploadQueue.EnqueueAsync(new SegmentInfo(filePath, sessionId, seqNum));
```

**Новое:**
```csharp
// 1. НОВОЕ: persist в SQLite
try
{
    _db.InsertSegment(
        filePath: filePath,
        sessionId: sessionId,
        sequenceNum: seqNum,
        sizeBytes: new FileInfo(filePath).Length,
        recordedAt: File.GetCreationTimeUtc(filePath).ToString("o")
    );
}
catch (Exception ex)
{
    _logger.LogWarning(ex, "Failed to persist segment to SQLite");
}

// 2. СТАРОЕ: по-прежнему в UploadQueue
await _uploadQueue.EnqueueAsync(new SegmentInfo(filePath, sessionId, seqNum));
```

**Проверка:**
- [ ] Новый сегмент → запись в `pending_segments` с `status=NEW`
- [ ] UploadQueue продолжает работать (presign → PUT → confirm)
- [ ] При ошибке SQLite — сегмент всё равно уходит через UploadQueue

---

## 3.5 Валидация dual-write

После внедрения dual-write для всех 4 типов — валидировать что данные идентичны:

```
Шаг 1: Запустить агент на 10 минут
Шаг 2: Проверить SQLite:
  SELECT data_type, COUNT(*) FROM pending_activity GROUP BY data_type
  SELECT status, COUNT(*) FROM pending_segments GROUP BY status
Шаг 3: Сравнить с количеством отправленных через старые sink'и (логи)
Шаг 4: Убедиться что все event_id уникальны
```

---

## Чеклист фазы 3

- [ ] 3.1 FocusIntervalSink: dual-write (SQLite + RAM queue)
- [ ] 3.2 InputEventSink: dual-write + clipboard content_hash
- [ ] 3.3 AuditEventSink: dual-write (pending_activity + RAM queue)
- [ ] 3.4 WatchSegments: dual-write (pending_segments + UploadQueue)
- [ ] 3.5 Валидация: данные в SQLite совпадают с отправленными
- [ ] Агент компилируется
- [ ] Агент работает стабильно 30+ минут
- [ ] **Старая отправка работает без изменений** (UploadQueue, BackgroundService flush)
- [ ] При крашах — данные в SQLite сохраняются (проверить kill -9 и рестарт)
