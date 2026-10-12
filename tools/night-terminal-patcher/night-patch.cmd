@echo off
setlocal
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0night-patch.ps1" %*
exit /b %errorlevel%
