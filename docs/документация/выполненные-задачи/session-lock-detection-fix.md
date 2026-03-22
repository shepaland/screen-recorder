# Задача: Исправить определение блокировки экрана в Windows Agent

**Приоритет:** High
**Тип:** Bug
**Компонент:** windows-agent-csharp / SessionWatcher

---

## Проблема

Устройство DESKTOP-J15ON4Q показывает статус `recording` даже когда экран заблокирован и пользователь неактивен.

### Корневая причина

`SessionWatcher` использует `SystemEvents.SessionSwitch` (Microsoft.Win32) для отслеживания событий блокировки/разблокировки экрана. Этот API **требует Windows message pump** (цикл обработки оконных сообщений) для доставки событий.

Kadero Agent работает как **Windows Service** (Session 0) через `Microsoft.Extensions.Hosting.WindowsServices`. В этом контексте message pump **отсутствует**, поэтому:

- Подписка `SystemEvents.SessionSwitch += OnSessionSwitch` выполняется, но **обработчик никогда не вызывается**
- `_isSessionActive` навсегда остаётся `true` (инициализация: `private volatile bool _isSessionActive = true`)
- Агент продолжает отправлять `status=recording` в heartbeat даже при заблокированном экране
- В таблице `device_audit_events` для DESKTOP-J15ON4Q **0 событий** SESSION_LOCK/SESSION_UNLOCK (подтверждено запросом к БД)

### Последствия бага

1. **Некорректный статус в UI** — администратор видит `recording`, хотя пользователь не работает
2. **Запись заблокированного экрана** — FFmpeg продолжает писать чёрный/статичный экран → расход диска и трафика
3. **Некорректная аналитика активности** — время recording завышено, idle-время не учитывается
4. **Heartbeat флаг `session_locked` всегда `false`** — сервер не может определить реальное состояние сессии

### Затронутые файлы

| Файл | Проблема |
|------|----------|
| `Audit/SessionWatcher.cs:124` | `SystemEvents.SessionSwitch` не работает без message pump |
| `Audit/SessionWatcher.cs:28` | `_isSessionActive = true` — инициализация без проверки текущего состояния |
| `Service/AgentService.cs:173` | `SyncStateWithRecording()` зависит от `_sessionWatcher.IsSessionActive` — всегда true |
| `Command/HeartbeatService.cs:81` | `session_locked = _commandHandler.IsPausedByLock` — всегда false |
| `Command/CommandHandler.cs:61-74` | `PauseRecordingAsync()` никогда не вызывается → запись не останавливается |

---

## Решение: WTS API через P/Invoke

Заменить `SystemEvents.SessionSwitch` на прямое использование **WTS API** (`WTSRegisterSessionNotification`) через P/Invoke. Этот подход не требует message pump и UI.

### Вариант реализации: Message-only window + WTSRegisterSessionNotification

Windows Service не получает WM_WTSSESSION_CHANGE напрямую. Решение — создать **message-only window** (HWND_MESSAGE) в отдельном потоке с message loop. Это невидимое окно без UI, единственная цель — получать WTS-нотификации.

### Шаги реализации

#### 1. Создать `WtsSessionNotifier` (новый класс)

Расположение: `Audit/WtsSessionNotifier.cs`

Ответственность:
- Создаёт message-only window (parent = HWND_MESSAGE) в отдельном потоке
- Вызывает `WTSRegisterSessionNotification(hwnd, NOTIFY_FOR_ALL_SESSIONS)`
- Обрабатывает `WM_WTSSESSION_CHANGE` в WndProc
- Генерирует события через callback/event для SessionWatcher

P/Invoke сигнатуры:
```csharp
[DllImport("wtsapi32.dll", SetLastError = true)]
static extern bool WTSRegisterSessionNotification(IntPtr hWnd, int dwFlags);

[DllImport("wtsapi32.dll", SetLastError = true)]
static extern bool WTSUnRegisterSessionNotification(IntPtr hWnd);

// Константы
const int NOTIFY_FOR_ALL_SESSIONS = 1;
const int WM_WTSSESSION_CHANGE = 0x02B1;

// WParam values для WM_WTSSESSION_CHANGE
const int WTS_SESSION_LOCK = 0x7;
const int WTS_SESSION_UNLOCK = 0x8;
const int WTS_SESSION_LOGON = 0x5;
const int WTS_SESSION_LOGOFF = 0x6;
const int WTS_CONSOLE_CONNECT = 0x1;
const int WTS_REMOTE_CONNECT = 0x3;
const int WTS_CONSOLE_DISCONNECT = 0x2;
const int WTS_REMOTE_DISCONNECT = 0x4;
```

Message-only window (без UI):
```csharp
// Создать окно в выделенном потоке с message loop
var thread = new Thread(() =>
{
    // RegisterClass + CreateWindowEx с parent = new IntPtr(-3) // HWND_MESSAGE
    // WTSRegisterSessionNotification(hwnd, NOTIFY_FOR_ALL_SESSIONS)
    // MSG loop: GetMessage → TranslateMessage → DispatchMessage
});
thread.IsBackground = true;
thread.SetApartmentState(ApartmentState.STA);
thread.Start();
```

#### 2. Модифицировать `SessionWatcher`

- Убрать зависимость от `SystemEvents.SessionSwitch`
- Использовать `WtsSessionNotifier` для получения событий
- Всю текущую логику `OnSessionSwitch` переиспользовать — только источник событий меняется
- Маппинг WTS → SessionSwitchReason:

| WTS Event | Текущий SessionSwitchReason | Действие |
|-----------|---------------------------|----------|
| WTS_SESSION_LOCK (7) | SessionLock | `_isSessionActive = false`, PauseRecording |
| WTS_SESSION_UNLOCK (8) | SessionUnlock | `_isSessionActive = true`, ResumeRecording |
| WTS_SESSION_LOGON (5) | SessionLogon | `_isSessionActive = true`, HandleSessionLogon |
| WTS_SESSION_LOGOFF (6) | SessionLogoff | `_isSessionActive = false`, PauseRecording |
| WTS_CONSOLE_CONNECT (1) | ConsoleConnect | `_isSessionActive = true`, ResumeRecording |
| WTS_REMOTE_CONNECT (3) | RemoteConnect | `_isSessionActive = true`, ResumeRecording |

#### 3. Инициализация: проверить текущее состояние сессии

При старте `SessionWatcher` — однократно запросить текущее состояние через WTS API (`WTSEnumerateSessionsW`), чтобы установить корректное начальное значение `_isSessionActive`. Класс `UserSessionInfo` уже содержит нужные P/Invoke — переиспользовать.

```csharp
// При старте проверить: есть ли активная сессия?
_isSessionActive = _userSessionInfo.GetCurrentUsername() != null
    && IsSessionUnlocked(); // Новый метод: WTSQuerySessionInformation → WTSConnectState
```

#### 4. Fallback: поллинг WTS состояния (защитная мера)

В `SyncStateWithRecording()` (вызывается каждые 30 секунд) добавить **поллинг-проверку** через WTS API как страховку от потери WM_WTSSESSION_CHANGE:

```csharp
// В SyncStateWithRecording():
// Каждые 30 сек — проверить WTS state для текущей user session
// Если WTSConnectState != WTSActive → _isSessionActive = false
```

Это даёт двойную надёжность: event-driven (WTS notifications) + polling (WTS query).

---

## Что НЕ нужно менять

- **Control-plane** — сервер уже принимает `session_locked` в heartbeat, просто не использует. Когда агент начнёт корректно отправлять статус `idle`, всё заработает.
- **HeartbeatService** — уже отправляет `session_locked = _commandHandler.IsPausedByLock`, после фикса значение будет корректным.
- **AgentState / AgentStateExtensions** — состояния `Idle`, `AwaitingUser` уже определены и маппятся в heartbeat.
- **CommandHandler.PauseRecordingAsync/ResumeRecordingAsync** — логика корректна, проблема в том, что они не вызываются.

---

## Тестирование

### Сценарии для проверки

1. **Lock/Unlock**: Заблокировать экран → heartbeat статус меняется на `idle`, `session_locked=true`. Разблокировать → статус возвращается в `recording`, запись возобновляется.
2. **Logon/Logoff**: Выйти из учётной записи → статус `idle`. Войти → авто-старт записи.
3. **RDP disconnect/reconnect**: Отключить RDP → статус `idle` или `desktop_unavailable`. Подключиться → запись возобновляется.
4. **Старт сервиса без пользователя**: Перезагрузка → сервис стартует → статус `awaiting_user` (не `recording`).
5. **device_audit_events**: Проверить, что SESSION_LOCK/UNLOCK/LOGON/LOGOFF записываются в БД.
6. **Поллинг fallback**: Убить message-only window → через 30 сек SyncStateWithRecording должен поймать блокировку через WTS query.

### Как проверить

- Устройство: DESKTOP-J15ON4Q (`37b945aa-2b10-472b-a959-4ab2ce250a6a`)
- Заблокировать экран (Win+L) и подождать 1-2 heartbeat-цикла (30 сек)
- SQL: `SELECT status, last_heartbeat_ts FROM devices WHERE id = '37b945aa-...'`
- SQL: `SELECT event_type, event_ts FROM device_audit_events WHERE device_id = '37b945aa-...' AND event_type LIKE 'SESSION_%' ORDER BY event_ts DESC LIMIT 10`

---

## Оценка объёма

| Компонент | Действие | Сложность |
|-----------|----------|-----------|
| `Audit/WtsSessionNotifier.cs` | Новый класс: P/Invoke + message-only window + message loop | Средняя |
| `Audit/SessionWatcher.cs` | Заменить SystemEvents на WtsSessionNotifier, добавить начальную проверку | Низкая |
| `Service/AgentService.cs` | Добавить WTS query в SyncStateWithRecording (fallback) | Низкая |
| `Program.cs` | Зарегистрировать WtsSessionNotifier в DI | Минимальная |

Основной риск: корректная работа message-only window в Session 0 (Service context). WTSRegisterSessionNotification задокументирован для работы в сервисах — это штатный механизм.
