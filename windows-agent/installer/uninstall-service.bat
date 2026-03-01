@echo off
echo Stopping PRG Screen Recorder Service...
sc stop PRGScreenRecorder
timeout /t 5 /nobreak > nul
echo Removing service...
sc delete PRGScreenRecorder
echo Service removed.
