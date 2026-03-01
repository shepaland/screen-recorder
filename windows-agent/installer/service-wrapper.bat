@echo off
setlocal

set APP_DIR=%~1
if "%APP_DIR%"=="" set APP_DIR=%~dp0

echo Installing PRG Screen Recorder Service...

sc create PRGScreenRecorder ^
    binPath= "\"%APP_DIR%\jre\bin\java.exe\" -jar \"%APP_DIR%\agent.jar\" --service" ^
    DisplayName= "PRG Screen Recorder" ^
    start= auto ^
    description= "PRG Screen Recorder Agent - captures and uploads screen recordings"

sc failure PRGScreenRecorder reset= 86400 actions= restart/60000/restart/120000/restart/300000

echo Service installed successfully.
