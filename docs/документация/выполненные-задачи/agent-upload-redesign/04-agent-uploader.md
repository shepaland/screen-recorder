# Фаза 4: Агент — DataSyncService (новый Uploader)

> **Цель:** Создать новый `DataSyncService` (BackgroundService) — единый поток отправки данных с round-robin. Удалить старые sink flush-loops и UploadQueue.
>
> **Зависит от:** Фазы 2 (SQLite v2), 3 (Collector refactor)
> **Блокирует:** Фазу 5 (Heartbeat refactor)

---

## 4.1 `ActivityUploader.cs` — batch-отправка activity

**Файл:** `Upload/ActivityUploader.cs` (НОВЫЙ)

**Ответственность:** Формирование HTTP-запросов для отправки activity-данных из SQLite на сервер.

```csharp
public class ActivityUploader
{
    private readonly ApiClient _apiClient;
    private readonly LocalDatabase _db;
    private readonly string _ingestBaseUrl;
    private readonly string _deviceId;

    /// <summary>
    /// Отправить batch focus intervals.
    /// Возвращает true если все записи приняты сервером.
    /// </summary>
    public async Task<bool> SendFocusIntervalsAsync(CancellationToken ct)
    {
        var items = _db.GetPendingActivity("FOCUS_INTERVAL", limit: 100);
        if (items.Count == 0) return true;

        var ids = items.Select(i => i.Id).ToList();
        _db.UpdateActivityStatus(ids, "QUEUED");

        try
        {
            var intervals = items.Select(i =>
                JsonSerializer.Deserialize<FocusInterval>(i.Payload, _jsonOptions)
            ).ToList();

            var request = new { device_id = _deviceId, username = _username, intervals };
            var response = await _apiClient.PostAsync(
                $"{_ingestBaseUrl}/activity/focus-intervals", request, ct);

            if (response.IsSuccessStatusCode)
            {
                _db.UpdateActivityStatus(ids, "SERVER_SIDE_DONE");
                return true;
            }
            else if (response.StatusCode == HttpStatusCode.Conflict)
            {
                // 409 = сервер уже имеет эти данные
                _db.UpdateActivityStatus(ids, "SERVER_SIDE_DONE");
                return true;
            }
            else
            {
                _db.UpdateActivityStatusWithRetry(ids, "PENDING",
                    $"HTTP {(int)response.StatusCode}");
                return false;
            }
        }
        catch (Exception ex)
        {
            _db.UpdateActivityStatusWithRetry(ids, "PENDING", ex.Message);
            return false;
        }
    }

    /// <summary>
    /// Отправить batch input events (mouse + keyboard + scroll + clipboard).
    /// </summary>
    public async Task<bool> SendInputEventsAsync(CancellationToken ct)
    {
        var types = new[] { "MOUSE_CLICK", "KEYBOARD_METRIC", "SCROLL", "CLIPBOARD" };
        var items = _db.GetPendingActivity(types, limit: 500);
        if (items.Count == 0) return true;

        var ids = items.Select(i => i.Id).ToList();
        _db.UpdateActivityStatus(ids, "QUEUED");

        try
        {
            // Группировать по типу для формирования request body
            var mouseClicks = DeserializeByType<MouseClickEvent>(items, "MOUSE_CLICK");
            var keyboardMetrics = DeserializeByType<KeyboardMetricEvent>(items, "KEYBOARD_METRIC");
            var scrollEvents = DeserializeByType<ScrollEvent>(items, "SCROLL");
            var clipboardEvents = DeserializeByType<ClipboardEvent>(items, "CLIPBOARD");

            var request = new
            {
                device_id = _deviceId,
                username = _username,
                mouse_clicks = mouseClicks,
                keyboard_metrics = keyboardMetrics,
                scroll_events = scrollEvents,
                clipboard_events = clipboardEvents
            };

            var response = await _apiClient.PostAsync(
                $"{_ingestBaseUrl}/activity/input-events", request, ct);

            if (response.IsSuccessStatusCode || response.StatusCode == HttpStatusCode.Conflict)
            {
                _db.UpdateActivityStatus(ids, "SERVER_SIDE_DONE");
                return true;
            }

            _db.UpdateActivityStatusWithRetry(ids, "PENDING",
                $"HTTP {(int)response.StatusCode}");
            return false;
        }
        catch (Exception ex)
        {
            _db.UpdateActivityStatusWithRetry(ids, "PENDING", ex.Message);
            return false;
        }
    }

    /// <summary>
    /// Отправить batch audit events.
    /// </summary>
    public async Task<bool> SendAuditEventsAsync(CancellationToken ct)
    {
        // Аналогично SendFocusIntervalsAsync, endpoint: /audit-events
        // ...
    }
}
```

**Проверка:**
- [ ] `SendFocusIntervalsAsync` — берёт до 100 FOCUS_INTERVAL, отправляет, ставит SERVER_SIDE_DONE
- [ ] `SendInputEventsAsync` — берёт до 500 input events, группирует по типу, отправляет
- [ ] `SendAuditEventsAsync` — берёт до 100 AUDIT_EVENT, отправляет
- [ ] При ошибке HTTP — записи → PENDING, retry_count++
- [ ] При 409 — записи → SERVER_SIDE_DONE

---

## 4.2 `DataSyncService.cs` — round-robin BackgroundService

**Файл:** `Upload/DataSyncService.cs` (НОВЫЙ)

```csharp
public class DataSyncService : BackgroundService
{
    private readonly ActivityUploader _activityUploader;
    private readonly SegmentUploader _segmentUploader;
    private readonly LocalDatabase _db;
    private readonly ILogger<DataSyncService> _logger;

    // Координация с HeartbeatService
    private volatile bool _serverAvailable = false;
    private readonly ManualResetEventSlim _serverAvailableEvent = new(false);

    /// <summary>
    /// Вызывается HeartbeatService при успешном heartbeat.
    /// </summary>
    public void SetServerAvailable(bool available)
    {
        _serverAvailable = available;
        if (available)
            _serverAvailableEvent.Set();
        else
            _serverAvailableEvent.Reset();
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        _logger.LogInformation("DataSyncService started");

        while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                // Ждать пока сервер доступен
                _serverAvailableEvent.Wait(stoppingToken);

                // Round-robin loop
                bool hasData = true;
                while (_serverAvailable && hasData && !stoppingToken.IsCancellationRequested)
                {
                    // Шаг A: batch activity
                    bool activitySent = await SendActivityBatchAsync(stoppingToken);

                    if (!_serverAvailable) break;

                    // Шаг B: 1 видеосегмент
                    bool segmentSent = await SendOneVideoSegmentAsync(stoppingToken);

                    if (!_serverAvailable) break;

                    // Если ничего не отправлено — данных нет
                    hasData = activitySent || segmentSent;
                }

                // Нет данных — подождать 5s, потом проверить снова
                if (_serverAvailable && !stoppingToken.IsCancellationRequested)
                {
                    // Cleanup между циклами
                    await CleanupAsync(stoppingToken);

                    await Task.Delay(5000, stoppingToken);
                }
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                break;
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "DataSyncService error, pausing 10s");
                await Task.Delay(10000, stoppingToken);
            }
        }

        _logger.LogInformation("DataSyncService stopped");
    }

    /// <summary>
    /// Шаг A: отправить batch activity (focus + input + audit).
    /// Возвращает true если хоть что-то было отправлено.
    /// </summary>
    private async Task<bool> SendActivityBatchAsync(CancellationToken ct)
    {
        bool anySent = false;

        // Focus intervals
        if (_serverAvailable)
        {
            bool sent = await _activityUploader.SendFocusIntervalsAsync(ct);
            if (!sent) _serverAvailable = false; // сервер не ответил — стоп
            anySent |= sent;
        }

        // Input events
        if (_serverAvailable)
        {
            bool sent = await _activityUploader.SendInputEventsAsync(ct);
            if (!sent) _serverAvailable = false;
            anySent |= sent;
        }

        // Audit events
        if (_serverAvailable)
        {
            bool sent = await _activityUploader.SendAuditEventsAsync(ct);
            if (!sent) _serverAvailable = false;
            anySent |= sent;
        }

        return anySent;
    }

    /// <summary>
    /// Шаг B: отправить ОДИН видеосегмент.
    /// Возвращает true если сегмент был отправлен.
    /// </summary>
    private async Task<bool> SendOneVideoSegmentAsync(CancellationToken ct)
    {
        var segment = _db.GetNextPendingSegment();
        if (segment == null) return false;

        _db.UpdateSegmentStatus(segment.Id, "QUEUED");

        try
        {
            // Проверить что файл существует
            if (!File.Exists(segment.FilePath))
            {
                _db.UpdateSegmentStatus(segment.Id, "EVICTED");
                _logger.LogWarning("Segment file not found, marking EVICTED: {Path}", segment.FilePath);
                return true; // technically processed
            }

            // SHA-256
            if (string.IsNullOrEmpty(segment.ChecksumSha256))
            {
                var bytes = await File.ReadAllBytesAsync(segment.FilePath, ct);
                using var sha = SHA256.Create();
                segment.ChecksumSha256 = Convert.ToHexString(sha.ComputeHash(bytes)).ToLowerInvariant();
            }

            // Presign → PUT S3 → Confirm
            bool success = await _segmentUploader.UploadSegmentAsync(segment, ct);

            if (success)
            {
                _db.UpdateSegmentStatus(segment.Id, "SERVER_SIDE_DONE");
                try { File.Delete(segment.FilePath); } catch { }
                return true;
            }
            else
            {
                _db.SetSegmentError(segment.Id, "Upload failed");
                return false; // сервер проблемный — остановить
            }
        }
        catch (Exception ex)
        {
            _db.SetSegmentError(segment.Id, ex.Message);
            _logger.LogError(ex, "Failed to upload segment {Id}", segment.Id);
            return false;
        }
    }

    /// <summary>
    /// Cleanup: удалить старые записи + incremental vacuum.
    /// </summary>
    private async Task CleanupAsync(CancellationToken ct)
    {
        try
        {
            // Retention: 72 часа максимум
            var maxRetention = TimeSpan.FromHours(72);
            var serverSideDoneAge = TimeSpan.FromHours(1);
            var evictedAge = TimeSpan.FromHours(24);

            // Activity cleanup
            int deletedActivity = _db.CleanupActivity(serverSideDoneAge, evictedAge, maxRetention);

            // Segments cleanup (сначала удалить файлы старше 72ч)
            var expiredSegments = _db.GetExpiredSegments(maxRetention);
            foreach (var seg in expiredSegments)
            {
                try { File.Delete(seg.FilePath); } catch { }
            }
            int deletedSegments = _db.CleanupSegments(maxRetention);

            // Incremental vacuum
            _db.IncrementalVacuum();

            if (deletedActivity > 0 || deletedSegments > 0)
            {
                _logger.LogInformation(
                    "Cleanup: {ActivityDeleted} activity, {SegmentsDeleted} segments removed",
                    deletedActivity, deletedSegments);
            }
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Cleanup failed");
        }
    }
}
```

**Проверка:**
- [ ] DataSyncService запускается как BackgroundService
- [ ] Ждёт `_serverAvailableEvent` (не потребляет CPU)
- [ ] При `SetServerAvailable(true)` — начинает round-robin
- [ ] Round-robin: activity batch → 1 video → activity batch → 1 video
- [ ] При `SetServerAvailable(false)` — завершает текущий запрос и засыпает
- [ ] При отсутствии данных — sleep 5s + cleanup
- [ ] 72-часовой retention работает
- [ ] `PRAGMA incremental_vacuum` вызывается

---

## 4.3 Удаление старых sink flush-loops

**После проверки что DataSyncService работает стабильно:**

### 4.3.1 `FocusIntervalSink.cs`

- Удалить наследование `BackgroundService`
- Удалить `ExecuteAsync()` (flush loop)
- Удалить `_queue` (ConcurrentQueue)
- Удалить `FlushAsync()` (HTTP POST)
- Оставить только `Enqueue()` → `_db.InsertActivity()` (из Фазы 3, убрать dual-write)
- Сделать обычным классом (не BackgroundService)

### 4.3.2 `InputEventSink.cs`

- Удалить наследование `BackgroundService`
- Удалить `ExecuteAsync()` (flush loop)
- Удалить все `ConcurrentQueue` поля (`_mouseClicks`, `_keyboardMetrics`, `_scrollEvents`, `_clipboardEvents`)
- Удалить `FlushAsync()` (HTTP POST)
- Оставить только `HandleInputEvents()` → `_db.InsertActivity()` (убрать dual-write)
- Сделать обычным классом

### 4.3.3 `AuditEventSink.cs`

- Удалить наследование `BackgroundService`
- Удалить `ExecuteAsync()` (flush loop)
- Удалить `_queue` (ConcurrentQueue)
- Удалить `FlushAsync()` (HTTP POST)
- Оставить только `Publish()` → `_db.InsertActivity()` (убрать dual-write)
- Сделать обычным классом

### 4.3.4 `UploadQueue.cs`

- **Удалить файл целиком**
- Убрать BoundedChannel, ProcessLoop, RetryPendingLoop
- Убрать регистрацию в DI

### 4.3.5 `WatchSegments` (CommandHandler)

- Убрать вызов `_uploadQueue.EnqueueAsync()` (dual-write)
- Оставить только `_db.InsertSegment()` (из Фазы 3)

### 4.3.6 `AgentService.cs`

- Убрать `_uploadQueue.StartProcessing()` / `StopProcessing()`
- Убрать enqueue pending segments из startup (DataSyncService подхватит из SQLite)

### 4.3.7 DI Registration

```csharp
// Убрать:
services.AddSingleton<UploadQueue>();
// services.AddHostedService<FocusIntervalSink>();  — больше не BackgroundService
// services.AddHostedService<InputEventSink>();     — больше не BackgroundService
// services.AddHostedService<AuditEventSink>();     — больше не BackgroundService

// Добавить:
services.AddSingleton<FocusIntervalSink>();   // обычный singleton
services.AddSingleton<InputEventSink>();      // обычный singleton
services.AddSingleton<AuditEventSink>();      // обычный singleton
services.AddSingleton<ActivityUploader>();
services.AddSingleton<DataSyncService>();
services.AddHostedService(sp => sp.GetRequiredService<DataSyncService>());
```

**Проверка:**
- [ ] Агент компилируется без UploadQueue
- [ ] FocusIntervalSink, InputEventSink, AuditEventSink — обычные классы
- [ ] Все данные проходят только через SQLite → DataSyncService → Server
- [ ] Нет утечек памяти (ConcurrentQueue удалены)
- [ ] Startup не зависит от UploadQueue

---

## 4.4 `SegmentUploader.cs` — упрощение

**Файл:** `Upload/SegmentUploader.cs`

**Убрать:**
- Логику retry (retry теперь через state machine в DataSyncService)
- Вызовы `_db.SavePendingSegment()` / `_db.MarkSegmentUploaded()` (старые методы)
- Вызов `SessionManager.InvalidateSession()` (сессия управляется выше)

**Оставить:**
- Presign → PUT S3 → Confirm (core upload logic)
- SHA-256 вычисление
- HTTP error handling (400/404/409 → return false/true)

**Новая сигнатура:**
```csharp
/// <summary>
/// Отправить один сегмент: presign → PUT S3 → confirm.
/// Возвращает true при успехе (сервер подтвердил ACK).
/// </summary>
public async Task<bool> UploadSegmentAsync(PendingSegment segment, CancellationToken ct)
```

**Проверка:**
- [ ] Presign → PUT → Confirm работает
- [ ] 200 confirmed → return true
- [ ] 400 → return true (discard)
- [ ] 409 → return true (already exists)
- [ ] 404 → return false (session not found)
- [ ] Timeout → return false

---

## 4.5 `SegmentFileManager.cs` — eviction по статусу

**Файл:** `Storage/SegmentFileManager.cs`

**Изменение:** Перед удалением файла проверять статус в SQLite:

```csharp
public void EvictOldSegments()
{
    var totalSize = GetTotalSegmentSize();
    if (totalSize <= _maxBufferBytes) return;

    // 1. Сначала удалить SERVER_SIDE_DONE (уже на сервере)
    var doneSegments = _db.GetSegmentsForEviction()
        .Where(s => s.Status == "SERVER_SIDE_DONE")
        .OrderBy(s => s.CreatedTs);

    foreach (var seg in doneSegments)
    {
        DeleteFile(seg.FilePath);
        _db.UpdateSegmentStatus(seg.Id, "EVICTED");
        totalSize -= seg.SizeBytes;
        if (totalSize <= _maxBufferBytes) return;
    }

    // 2. Затем NEW/PENDING (данные не на сервере — WARNING)
    var pendingSegments = _db.GetSegmentsForEviction()
        .Where(s => s.Status is "NEW" or "PENDING")
        .OrderBy(s => s.CreatedTs);

    foreach (var seg in pendingSegments)
    {
        _logger.LogWarning("Evicting unsent segment {Id} ({Status}) due to disk pressure",
            seg.Id, seg.Status);
        DeleteFile(seg.FilePath);
        _db.UpdateSegmentStatus(seg.Id, "EVICTED");
        totalSize -= seg.SizeBytes;
        if (totalSize <= _maxBufferBytes) return;
    }

    // 3. QUEUED/SENDED — НЕ УДАЛЯТЬ (в процессе отправки)
}
```

**Проверка:**
- [ ] SERVER_SIDE_DONE файлы удаляются первыми
- [ ] NEW/PENDING удаляются только при disk pressure + WARNING в лог
- [ ] QUEUED/SENDED не удаляются
- [ ] Статус обновляется на EVICTED

---

## Чеклист фазы 4

- [ ] 4.1 ActivityUploader: batch-отправка focus/input/audit
- [ ] 4.2 DataSyncService: round-robin BackgroundService
- [ ] 4.3 Удалены: UploadQueue, sink flush-loops, dual-write
- [ ] 4.4 SegmentUploader: упрощён
- [ ] 4.5 SegmentFileManager: eviction по статусу
- [ ] Агент компилируется
- [ ] Все данные проходят: Collector → SQLite → DataSyncService → Server
- [ ] Round-robin работает: activity ↔ video чередуются
- [ ] Cleanup (72ч retention) работает
- [ ] DataSyncService засыпает при server_available=false
