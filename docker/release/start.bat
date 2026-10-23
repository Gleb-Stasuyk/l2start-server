@echo off
setlocal EnableDelayedExpansion
cd /d "%~dp0"
docker info >nul 2>&1
if errorlevel 1 (
  echo Docker Desktop is not running. Start it and try again.
  if /i not "%~1"=="nopause" pause
  exit /b 1
)
if not exist ".env" (
  echo Run install.bat first.
  if /i not "%~1"=="nopause" pause
  exit /b 1
)
echo Starting L2Start local server ...
docker compose up -d
if errorlevel 1 goto :fail
echo Waiting for the game server (first start takes a few minutes) ...
set /a T=0
:wait
set "HS="
for /f %%S in ('docker inspect -f "{{.State.Health.Status}}" l2l-game 2^>nul') do set "HS=%%S"
if /i "!HS!"=="healthy" goto :ready
set /a T+=1
if !T! GEQ 120 goto :slow
ping -n 6 127.0.0.1 >nul
goto :wait
:ready
echo L2Start local server is up: 127.0.0.1:2106. Accounts are created on first login.
if /i not "%~1"=="nopause" pause
exit /b 0
:slow
echo Game server not ready after 10 minutes. Logs: docker logs --tail 100 l2l-game
if /i not "%~1"=="nopause" pause
exit /b 1
:fail
echo docker compose failed - see messages above.
if /i not "%~1"=="nopause" pause
exit /b 1
