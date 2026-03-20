# Фаза 5: Агент — Heartbeat refactor

> **Цель:** Heartbeat работает как фиксированный 30s цикл, управляет флагом `server_available` для DataSyncService. Не вызывает upload напрямую.
>
> **Зависит от:** Фаза 4 (DataSyncService)
> **Блокирует:** Фазу 6 (тестирование)

---

## 5.1 HeartbeatService: интеграция с DataSyncService

**Файл:** `Command/HeartbeatService.cs`

### Текущее поведение:
```
loop:
  1. PUT /heartbeat → response
  2. Обработать device_settings, pending_commands
  3. Auto-start recording
  4. await Task.Delay(NextHeartbeatSec)
```

### Новое поведение:
```
loop:
  1. PUT /heartbeat → response
  2. Обработать device_settings, pending_commands
  3. Обновить upload_enabled:
     if (response OK && response.upload_enabled)
         _dataSyncService.SetServerAvailable(true)
     else
         _dataSyncService.SetServerAvailable(false)
  4. Auto-start recording
  5. await Task.Delay(30s)   ← ФИКСИРОВАННЫЙ, всегда 30 секунд
```

**Ключевые изменения:**

```csharp
protected override async Task ExecuteAsync(CancellationToken stoppingToken)
{
    // Ждать пока AuthManager аутентифицируется
    await WaitForAuth(stoppingToken);

    while (!stoppingToken.IsCancellationRequested)
    {
        try
        {
            var response = await SendHeartbeatAsync(stoppingToken);

            if (response != null)
            {
                // Heartbeat успешен
                ProcessDeviceSettings(response.DeviceSettings);
                ProcessPendingCommands(response.PendingCommands);

                // НОВОЕ: обновить server_available
                bool uploadEnabled = response.UploadEnabled ?? true;
                _dataSyncService.SetServerAvailable(uploadEnabled);

                // Auto-start recording (как раньше)
                await CheckAutoStart(stoppingToken);
            }
            else
            {
                // Heartbeat неуспешен — сервер недоступен
                _dataSyncService.SetServerAvailable(false);
            }
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Heartbeat failed");
            _dataSyncService.SetServerAvailable(false);
        }

        // ФИКСИРОВАННЫЙ интервал 30 секунд
        // Не зависит от NextHeartbeatSec — heartbeat всегда ходит каждые 30s
        await Task.Delay(TimeSpan.FromSeconds(30), stoppingToken);
    }
}
```

**Проверка:**
- [ ] Heartbeat ходит каждые 30 секунд (не backoff)
- [ ] Успешный heartbeat → `SetServerAvailable(true)`
- [ ] Неуспешный heartbeat → `SetServerAvailable(false)`
- [ ] `upload_enabled: false` в response → `SetServerAvailable(false)`
- [ ] `upload_enabled: true` (или отсутствует) → `SetServerAvailable(true)`
- [ ] DataSyncService просыпается при первом успешном heartbeat
- [ ] DataSyncService засыпает при неудачном heartbeat

---

## 5.2 Парсинг `upload_enabled` из heartbeat response

**Файл:** `Configuration/ServerConfig.cs` или DTO heartbeat response

**Изменение:** Добавить поле в модель heartbeat response:

```csharp
// HeartbeatResponse (или аналогичный DTO в агенте)
[JsonPropertyName("upload_enabled")]
public bool? UploadEnabled { get; set; }
```

В `HeartbeatService`:
```csharp
// Парсинг ответа — upload_enabled (default true если поле отсутствует)
bool uploadEnabled = heartbeatResponse?.UploadEnabled ?? true;
```

**Backward compatibility:** Если сервер ещё не обновлён (нет поля `upload_enabled` в response), `?? true` гарантирует что Uploader работает.

**Проверка:**
- [ ] Старый сервер (без upload_enabled) → агент считает upload разрешённым
- [ ] Новый сервер с `upload_enabled: true` → upload разрешён
- [ ] Новый сервер с `upload_enabled: false` → upload запрещён

---

## 5.3 Heartbeat metrics: данные из SQLite

**Файл:** `Command/HeartbeatService.cs` — формирование `metrics` в heartbeat request

**Текущее:**
```json
{
  "cpu_percent": 15,
  "memory_mb": 120,
  "disk_free_gb": 50,
  "segments_queued": 5
}
```

**Новое:**
```json
{
  "cpu_percent": 15,
  "memory_mb": 120,
  "disk_free_gb": 50,
  "segments_new": 5,
  "segments_queued": 2,
  "segments_pending": 3,
  "segments_done": 142,
  "segments_evicted": 0,
  "activity_new": 230,
  "activity_pending": 12,
  "activity_done": 5840,
  "db_size_bytes": 15728640,
  "oldest_unsent_segment_age_sec": 120,
  "oldest_unsent_activity_age_sec": 35
}
```

**Реализация:**
```csharp
private object BuildMetrics()
{
    var segmentCounts = _db.GetSegmentCountsByStatus();
    var activityCounts = _db.GetActivityCountsByStatus();
    var dbSize = new FileInfo(_db.DatabasePath).Length;

    return new
    {
        cpu_percent = GetCpuPercent(),
        memory_mb = GetMemoryMb(),
        disk_free_gb = GetDiskFreeGb(),
        segments_new = segmentCounts.GetValueOrDefault("NEW"),
        segments_queued = segmentCounts.GetValueOrDefault("QUEUED"),
        segments_pending = segmentCounts.GetValueOrDefault("PENDING"),
        segments_done = segmentCounts.GetValueOrDefault("SERVER_SIDE_DONE"),
        segments_evicted = segmentCounts.GetValueOrDefault("EVICTED"),
        activity_new = activityCounts.GetValueOrDefault("NEW"),
        activity_pending = activityCounts.GetValueOrDefault("PENDING"),
        activity_done = activityCounts.GetValueOrDefault("SERVER_SIDE_DONE"),
        db_size_bytes = dbSize,
        oldest_unsent_segment_age_sec = _db.GetOldestUnsentSegmentAgeSec(),
        oldest_unsent_activity_age_sec = _db.GetOldestUnsentActivityAgeSec()
    };
}
```

**Backward compatibility:** Сервер игнорирует неизвестные поля в metrics (Jackson).

**Проверка:**
- [ ] Heartbeat request содержит новые метрики
- [ ] Сервер принимает heartbeat без ошибок
- [ ] `segments_new` + `segments_queued` + `segments_pending` = реальное количество неотправленных

---

## 5.4 Удалить зависимость HeartbeatService от UploadQueue

**Файл:** `Command/HeartbeatService.cs`

- Убрать `_uploadQueue` из конструктора и DI
- Убрать `segments_queued` из старых метрик (заменён на новые в 5.3)
- Убрать любые вызовы `_uploadQueue.StartProcessing()` / `StopProcessing()` (если есть)

---

## 5.5 Сессионное управление

**Текущее:** `SessionManager.StartSessionAsync()` вызывается из `CommandHandler.StartRecording()` и из `UploadQueue.ProcessLoop()`.

**Новое:** `SessionManager` управляется `CommandHandler` (при старте/стопе записи). `DataSyncService` использует session_id из SQLite (записан Collector'ом при INSERT сегмента).

Если `DataSyncService` обнаруживает сегмент без валидной сессии на сервере (404 при presign):
1. НЕ создавать новую сессию
2. Оставить сегмент в PENDING
3. Ждать пока CommandHandler создаст/восстановит сессию
4. На следующем round-robin цикле — retry

**Проверка:**
- [ ] DataSyncService НЕ вызывает SessionManager.StartSessionAsync()
- [ ] При 404 от presign — сегмент → PENDING (не EVICTED)
- [ ] CommandHandler управляет сессией при start/stop recording

---

## Чеклист фазы 5

- [ ] 5.1 HeartbeatService: фиксированный 30s, SetServerAvailable()
- [ ] 5.2 Парсинг upload_enabled из heartbeat response
- [ ] 5.3 Новые метрики из SQLite в heartbeat request
- [ ] 5.4 Удалена зависимость от UploadQueue
- [ ] 5.5 Сессионное управление — только через CommandHandler
- [ ] Heartbeat работает стабильно каждые 30s
- [ ] DataSyncService реагирует на server_available
- [ ] Backward-compatible: старый сервер → upload работает
