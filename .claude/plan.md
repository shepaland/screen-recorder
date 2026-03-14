# План: Установщик KaderoAgent + очистка Windows

## Шаг 1: Очистка Windows-машины (192.168.1.135)
- Убить все процессы KaderoAgent.exe
- Удалить Windows Service KaderoAgent (`sc stop`, `sc delete`)
- Удалить HKCU\Run → KaderoAgent
- Удалить HKLM\Run → KaderoTray
- Удалить `C:\screen-recorder-agent\` (исходники, данные, credentials)
- Удалить schtasks KaderoAgent*
- Удалить старые PRGScreenRecorder записи из автозагрузки

## Шаг 2: Обновить appsettings.json
- DataPath: `C:\screen-recorder-agent` → `%PROGRAMDATA%\Kadero` (= `C:\ProgramData\Kadero`)
- Это совпадает с `{commonappdata}\Kadero` в setup.iss

## Шаг 3: Установить Inno Setup 6 на Windows-машину
- Скачать Inno Setup 6 (скрипт скачает через PowerShell)
- Установить в стандартную директорию

## Шаг 4: Скачать FFmpeg на Windows-машину
- Скачать ffmpeg essentials build с gyan.dev
- Извлечь ffmpeg.exe в `installer/ffmpeg/`

## Шаг 5: Скопировать исходники и собрать
- Скопировать весь windows-agent-csharp/ на Windows-машину
- Запустить `build.ps1` (publish + Inno Setup)
- Результат: `installer/Output/KaderoAgentSetup.exe`

## Шаг 6: Положить установщик в C:\kadero_install
- Создать `C:\kadero_install\`
- Скопировать `KaderoAgentSetup.exe`

## Шаг 7: Коммит и push
