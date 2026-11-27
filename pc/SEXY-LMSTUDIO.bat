@echo off
setlocal
title SEXY - share LM Studio and your chats over Tailscale
rem Run once (again after moving LM Studio). It:
rem   - stops the old llama-server (3030)
rem   - starts LM Studio's server on 1234 and the SEXY chat bridge on 1235
rem   - shares both with your Tailscale devices only
rem   - starts both again at every login

set "HERE=%~dp0"
set "LMS=%HERE%.lmstudio\bin\lms.exe"
set "BRIDGE=%HERE%sexy-bridge.ps1"
set "PORT=1234"
set "BRIDGE_PORT=1235"
set "STARTUP=%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup"

echo.
echo [1/6] Stopping the old llama-server on port 3030...
taskkill /F /IM llama-server.exe >nul 2>&1 && (echo      stopped.) || (echo      it wasn't running.)
tailscale serve --http=3030 off >nul 2>&1
for /f "tokens=1,* delims=:" %%A in ('schtasks /query /fo LIST 2^>nul ^| findstr /i /r "^TaskName:.*llama"') do (
  for /f "tokens=* delims= " %%N in ("%%B") do (
    schtasks /change /tn "%%N" /disable >nul 2>&1 && (echo      turned off startup task %%N) || (echo      couldn't turn off task %%N - run this file as admin to do it)
  )
)
if exist "%STARTUP%\*llama*" (
  if not exist "%HERE%old-startup" mkdir "%HERE%old-startup"
  move /Y "%STARTUP%\*llama*" "%HERE%old-startup\" >nul && echo      moved the llama-server startup entry to %HERE%old-startup
)

echo.
echo [2/6] Starting LM Studio's server on port %PORT%...
if not exist "%LMS%" (
  echo      Can't find %LMS% - open LM Studio once, then run this again.
  goto :end
)
"%LMS%" server start --port %PORT%

echo.
echo [3/6] Starting the SEXY chat bridge on port %BRIDGE_PORT% (keeps your chats on this PC)...
if not exist "%BRIDGE%" (
  echo      Can't find %BRIDGE% - put sexy-bridge.ps1 next to this file.
  goto :end
)
start "SEXY bridge" /min powershell -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "%BRIDGE%" -Port %BRIDGE_PORT%

echo.
echo [4/6] Sharing both with your Tailscale devices only (no firewall port opened)...
tailscale serve --bg --http=%PORT% http://127.0.0.1:%PORT%
tailscale serve --bg --http=%BRIDGE_PORT% http://127.0.0.1:%BRIDGE_PORT%
if errorlevel 1 echo      If Tailscale printed a link above, open it once to turn on "Serve" for your tailnet, then run this file again.

echo.
echo [5/6] Starting both automatically at every login...
> "%STARTUP%\SEXY LM Studio server.bat" (
  echo @echo off
  echo timeout /t 20 /nobreak ^>nul
  echo "%LMS%" server start --port %PORT%
  echo start "SEXY bridge" /min powershell -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "%BRIDGE%" -Port %BRIDGE_PORT%
)
echo      added "SEXY LM Studio server" to your Startup folder.

echo.
echo [6/6] Checking...
timeout /t 3 /nobreak >nul
for /f %%C in ('curl -s -o nul -w "%%{http_code}" http://127.0.0.1:%PORT%/api/v1/models') do set "CODE=%%C"
if "%CODE%"=="200" (echo      LM Studio answers on port %PORT%.) else (echo      LM Studio didn't answer yet [code %CODE%]. Open LM Studio - Developer - make sure the server is Running.)
for /f %%C in ('curl -s -o nul -w "%%{http_code}" http://127.0.0.1:%BRIDGE_PORT%/health') do set "BCODE=%%C"
if "%BCODE%"=="200" (echo      The chat bridge answers on port %BRIDGE_PORT%.) else (echo      The chat bridge didn't answer [code %BCODE%] - see %HERE%sexy-bridge.log)
tailscale serve status

echo.
echo Done. On your phone: turn on Tailscale and open SEXY - it finds this PC by itself.
echo.
echo To use your LM Studio plugins (web search, Wikipedia, JS sandbox...) from the phone, open
echo LM Studio - Developer - Server settings and turn on "Allow calling servers from mcp.json".

:end
echo.
pause
