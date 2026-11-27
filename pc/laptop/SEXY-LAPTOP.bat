@echo off
setlocal
title SEXY - share this laptop's llama.cpp (port 9931) with your phones
rem Run once (again after moving this folder). It makes http://127.0.0.1:9931 on this laptop reachable
rem from the SEXY app on your phones:
rem   - on your Wi-Fi        (port proxy + firewall rule for your Wi-Fi and Tailscale only)
rem   - over Tailscale       (tailscale serve --http=9931)
rem   - as 127.0.0.1:9931 on the phone itself (adb reverse, over USB or wireless debugging)
rem and keeps it working after reboots and Wi-Fi changes.

net session >nul 2>&1
if errorlevel 1 (
  echo Asking for administrator rights...
  powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
  exit /b
)

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0sexy-laptop.ps1" -Setup -Port 9931
echo.
pause
