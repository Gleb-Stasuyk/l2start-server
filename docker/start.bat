@echo off
setlocal EnableDelayedExpansion
cd /d "%~dp0"
rem l2start-server in Docker: start.  Usage: start.bat [solo^|online] [rebuild]

set "PROFILE=%~1"
if "%PROFILE%"=="" set "PROFILE=solo"
if not exist "profiles\%PROFILE%.env" (
  echo Unknown profile "%PROFILE%". Use: start.bat solo   or   start.bat online
  exit /b 1
)
set "BUILD="
if /i "%~2"=="rebuild" set "BUILD=--build"

docker info >nul 2>&1
if errorlevel 1 (
  echo Docker Desktop is not running. Start it and try again.
  pause
  exit /b 1
)

set "ENVFILES=--env-file profiles\%PROFILE%.env"
if exist ".env" set "ENVFILES=%ENVFILES% --env-file .env"

rem Ports 2106/7777 are shared with the old servers (l2-*, lw-*): only one server can run.
set "BUSY="
for /f %%N in ('docker ps --format "{{.Names}}" ^| findstr /x "l2-login l2-game lw-login lw-game"') do set "BUSY=!BUSY! %%N"
if defined BUSY (
  echo These containers are running and use ports 2106/7777:%BUSY%
  choice /c YN /m "Stop them now"
  if errorlevel 2 goto :abort
  docker stop%BUSY%
)

echo Starting l2start-server, profile %PROFILE%: l2s-db, l2s-db-init, l2s-login, l2s-game ...
docker compose %ENVFILES% up -d %BUILD%
if errorlevel 1 (
  echo docker compose failed - see messages above.
  pause
  exit /b 1
)

echo Waiting for the game server (first start can take a few minutes) ...
set /a TRIES=0
:wait
set "HS="
for /f %%S in ('docker inspect -f "{{.State.Health.Status}}" l2s-game 2^>nul') do set "HS=%%S"
if /i "%HS%"=="healthy" goto :ready
set /a TRIES+=1
if %TRIES% GEQ 120 goto :slow
ping -n 6 127.0.0.1 >nul
goto :wait

:ready
echo.
echo l2start-server is up: login 127.0.0.1:2106, game 127.0.0.1:7777 (profile %PROFILE%)
echo Logs: docker logs -f l2s-game
echo Stop: stop.bat
pause
exit /b 0

:slow
echo Game server is not ready after 10 minutes. Check: docker logs --tail 100 l2s-game
pause
exit /b 1

:abort
echo Cancelled. Stop the other server first, then run this again.
pause
exit /b 1
