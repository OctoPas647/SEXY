@echo off
setlocal
title SEXY - pair a phone for wireless debugging (127.0.0.1:9931 on the phone)
rem Once per phone. After pairing, the phone gets 127.0.0.1:9931 forwarded to this laptop
rem whenever Wireless debugging is on and it's on the same Wi-Fi.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0sexy-laptop.ps1" -Pair -Port 9931
echo.
pause
