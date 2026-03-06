# Ручная проверка Glass UI на Windows

## Предварительные условия

- Windows 10 или Windows 11
- .NET 8 SDK установлен: `C:\Users\shepaland\.dotnet\dotnet.exe`
- Проект собран:

```cmd
C:\Users\shepaland\.dotnet\dotnet.exe build C:\Users\shepaland\screen-recorder\windows-agent-csharp\src\KaderoAgent\KaderoAgent.csproj
```

Если проект ещё не скопирован на машину — см. раздел «Подготовка» в конце документа.

---

## 1. Автоматические тесты (консоль, без GUI)

```cmd
C:\Users\shepaland\.dotnet\dotnet.exe run ^
  --project C:\Users\shepaland\screen-recorder\windows-agent-csharp\src\KaderoAgent\KaderoAgent.csproj ^
  -- --test-ui
```

Ожидаемый вывод — **13 PASS, 0 FAIL**.

Проверяет: цвета GlassHelper, размеры окон, borderless-стиль, позицию StatusWindow в правом нижнем углу.

---

## 2. Проверка SetupForm (окно регистрации)

```cmd
C:\Users\shepaland\.dotnet\dotnet.exe run ^
  --project C:\Users\shepaland\screen-recorder\windows-agent-csharp\src\KaderoAgent\KaderoAgent.csproj ^
  -- --setup
```

**Что проверить:**

- Окно появляется **по центру экрана**
- **Без стандартной рамки Windows** (borderless) — кастомный тёмный title bar с кнопкой `✕`
- **Тёмный полупрозрачный фон** (если Win11 — с размытием Acrylic)
- Заголовок в title bar: «Настройка агента»
- Два поля ввода с тёмным фоном:
  - Адрес сервера (по умолчанию `https://`)
  - Токен регистрации (placeholder `drt_...`)
- Красная кнопка «Подключить» по центру
- **Перетаскивание**: зажать мышь на title bar или пустой области → окно двигается
- Кнопка `✕` в правом верхнем углу → закрывает окно
- При наведении на `✕` — цвет меняется на красный

---

## 3. Проверка StatusWindow + Tray

```cmd
C:\Users\shepaland\.dotnet\dotnet.exe run ^
  --project C:\Users\shepaland\screen-recorder\windows-agent-csharp\src\KaderoAgent\KaderoAgent.csproj ^
  -- --tray
```

### 3.1 Системный трей

- В области уведомлений (рядом с часами) появляется цветная иконка-кружок
- **Правый клик** по иконке → контекстное меню:
  - «Открыть Кадеро» (жирный шрифт)
  - «Статус: ...» (серый, неактивный)
  - ---
  - «Переподключиться»
  - «Перезапустить сервис»
  - ---
  - «О программе»
  - «Выход»
- **Двойной клик** по иконке → открывается StatusWindow

### 3.2 StatusWindow

- Окно появляется в **правом нижнем углу экрана**, прямо над панелью задач
- **Не перекрывает taskbar**
- **Borderless** — тёмный стеклянный фон, кастомный title bar
- Заголовок: «Кадеро»
- Секции с разделителями (тонкие горизонтальные линии):

| Секция | Содержимое |
|--------|------------|
| **Подключение** | Два цветных индикатора-кружка + текст статуса подключения и записи |
| **ID устройства** | Текстовая метка |
| **Параметры записи** | FPS, Качество, Сегмент, Heartbeat — серые подписи, белые значения |
| **Метрики** | CPU, RAM, Диск, Очередь, Последний heartbeat |
| **Настройки подключения** | Поля: Адрес сервера, Токен, Имя пользователя, Пароль + красная кнопка «Переподключиться» |

- **Перетаскивание**: зажать title bar или пустую область → окно двигается по экрану
- Кнопка `✕` → **скрывает** окно в трей (Hide), не закрывает приложение
- Иконка в трее продолжает работать после закрытия окна
- Текст — белый на тёмном фоне
- Заголовки секций — красные (#E53935)
- Подписи полей — полупрозрачный белый

---

## 4. Проверка AboutDialog

В контекстном меню трея → «О программе»

**Что проверить:**

- Borderless тёмное окно по центру экрана
- «Кадеро Agent» крупным шрифтом, красного цвета
- Строка с версией (серый текст)
- Описание: «Агент записи экрана для системы Кадеро»
- Красная кнопка «Закрыть» по центру
- Перетаскивается мышью за title bar или фон

---

## 5. Чек-лист

| # | Проверка | Ожидание | PASS/FAIL |
|---|----------|----------|-----------|
| 1 | `--test-ui` | 13 PASS, 0 FAIL | |
| 2 | SetupForm появляется | По центру, borderless, тёмный фон | |
| 3 | SetupForm drag | Перетаскивается мышью | |
| 4 | SetupForm кнопка `✕` | Закрывает окно | |
| 5 | SetupForm поля ввода | Тёмный фон, белый текст, placeholder | |
| 6 | Tray icon | Цветная иконка в системном трее | |
| 7 | Tray контекстное меню | 7 пунктов, «Открыть Кадеро» жирный | |
| 8 | StatusWindow позиция | Правый нижний угол, НЕ перекрывает taskbar | |
| 9 | StatusWindow borderless | Тёмный glass-стиль, без рамки Windows | |
| 10 | StatusWindow drag | Перетаскивается за title bar и фон | |
| 11 | StatusWindow `✕` | Скрывает окно (Hide), tray остаётся | |
| 12 | StatusWindow секции | Подключение, Параметры, Метрики, Настройки | |
| 13 | StatusWindow кнопка | «Переподключиться» — красная, кликабельная | |
| 14 | AboutDialog | Borderless, по центру, красный заголовок | |
| 15 | AboutDialog drag | Перетаскивается | |
| 16 | Acrylic blur (Win11) | Полупрозрачный фон с размытием через стекло | |
| 17 | Fallback (Win10) | Тёмный непрозрачный фон, скруглённые углы | |
| 18 | Шрифт | Segoe UI везде | |
| 19 | Цвета | Белый текст, красный акцент (#E53935), тёмный фон | |

---

## Примечания

- **Windows 11**: полный Acrylic blur + DWM скруглённые углы (нативные)
- **Windows 10**: тёмный полупрозрачный фон без blur (fallback), скруглённые углы через Region
- Без запущенного Windows Service (`--service`) StatusWindow покажет «Нет связи с сервисом» — **это нормально**
- Статусные индикаторы будут серыми (нет данных от сервиса) — **это нормально**

---

## Подготовка (если проект не скопирован)

### Установка .NET 8 SDK

```powershell
Invoke-WebRequest -Uri 'https://dot.net/v1/dotnet-install.ps1' -OutFile 'dotnet-install.ps1'
.\dotnet-install.ps1 -Channel 8.0 -InstallDir "$env:USERPROFILE\.dotnet"
```

### Копирование проекта

С macOS/Linux-машины (где есть исходники):

```bash
# Создать архив (без bin/obj)
tar czf /tmp/kadero-agent.tar.gz --exclude bin --exclude obj \
  -C windows-agent-csharp/src/KaderoAgent .

# Скопировать на Windows
scp /tmp/kadero-agent.tar.gz shepaland@192.168.1.135:C:/Users/shepaland/kadero-agent.tar.gz
```

На Windows:

```cmd
mkdir C:\Users\shepaland\screen-recorder\windows-agent-csharp\src\KaderoAgent
cd C:\Users\shepaland\screen-recorder\windows-agent-csharp\src\KaderoAgent
tar xzf C:\Users\shepaland\kadero-agent.tar.gz
del /s /q ._*
```

### Сборка

```cmd
C:\Users\shepaland\.dotnet\dotnet.exe build C:\Users\shepaland\screen-recorder\windows-agent-csharp\src\KaderoAgent\KaderoAgent.csproj
```

Ожидаемый результат: `Сборка успешно завершена. Предупреждений: 0. Ошибок: 0.`
