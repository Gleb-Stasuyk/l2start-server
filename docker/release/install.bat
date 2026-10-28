@echo off
setlocal EnableDelayedExpansion
cd /d "%~dp0"
rem L2Start local server: first install. Run again any time (keeps your characters).

where docker >nul 2>&1
if errorlevel 1 (
  echo Docker Desktop is not installed. Install it, start it, then run install.bat again.
  start "" "https://www.docker.com/products/docker-desktop/"
  pause
  exit /b 1
)
docker info >nul 2>&1
if errorlevel 1 (
  echo Docker Desktop is not running. Start Docker Desktop, wait until it is ready, then run install.bat again.
  pause
  exit /b 1
)

if not exist ".env" (
  for /f %%P in ('powershell -NoProfile -Command "-join ((48..57)+(65..90)+(97..122) | Get-Random -Count 24 | %% {[char]$_})"') do set "PW=%%P"
  set /p VER=<VERSION
  (
    echo L2S_VERSION=!VER!
    echo L2S_DB_PASSWORD=!PW!
  ) > .env
  echo Created .env ^(random local DB password^).
)

for /f "tokens=1,* delims==" %%A in ('findstr /b "L2S_VERSION=" .env') do set "VER=%%B"
if exist "image\l2start-server.tar" (
  echo Loading server image from image\l2start-server.tar ...
  docker load -i "image\l2start-server.tar" || goto :fail
) else (
  echo Downloading server image ghcr.io/gleb-stasuyk/l2start-server:!VER! ...
  docker compose pull || goto :fail
)

call "%~dp0start.bat" nopause
exit /b %errorlevel%

:fail
echo Install failed - see messages above.
pause
exit /b 1
