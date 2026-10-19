@echo off
setlocal EnableDelayedExpansion
cd /d "%~dp0"
rem Restore DB from gzip dump. Usage: restore.bat path\to\l2s-YYYYMMDD-HHMM.sql.gz [solo^|online]

set "FILE=%~1"
set "PROFILE=%~2"
if "%PROFILE%"=="" set "PROFILE=solo"
if "%FILE%"=="" (
  echo Usage: restore.bat backups\l2s-YYYYMMDD-HHMM.sql.gz [solo^|online]
  exit /b 1
)
if not exist "%FILE%" if exist "%~dp0..\%FILE%" set "FILE=%~dp0..\%FILE%"
if not exist "%FILE%" (
  echo restore: file not found: %~1
  exit /b 1
)
if not exist "profiles\%PROFILE%.env" (
  echo restore: unknown profile "%PROFILE%"
  exit /b 1
)

set "DBNAME=l2start"
set "DBPASS="
for /f "usebackq tokens=1,* delims==" %%A in (`findstr /b /c:"L2S_DB_NAME=" "profiles\%PROFILE%.env"`) do set "DBNAME=%%B"
for /f "usebackq tokens=1,* delims==" %%A in (`findstr /b /c:"L2S_DB_PASSWORD=" "profiles\%PROFILE%.env"`) do set "DBPASS=%%B"
if exist ".env" (
  for /f "usebackq tokens=1,* delims==" %%A in (`findstr /b /c:"L2S_DB_NAME=" ".env"`) do set "DBNAME=%%B"
  for /f "usebackq tokens=1,* delims==" %%A in (`findstr /b /c:"L2S_DB_PASSWORD=" ".env"`) do set "DBPASS=%%B"
)
if not defined DBPASS (
  echo restore: L2S_DB_PASSWORD not set
  exit /b 1
)

docker info >nul 2>&1 || (
  echo Docker Desktop is not running.
  exit /b 1
)

docker inspect l2s-db >nul 2>&1 || (
  echo restore: no l2s-db - run start.bat solo once first
  exit /b 1
)

echo restore: stopping game and login ...
docker stop l2s-game l2s-login 2>nul

for /f %%S in ('docker inspect -f "{{.State.Status}}" l2s-db 2^>nul') do set "ST=%%S"
if /i not "!ST!"=="running" (
  echo restore: starting l2s-db ...
  docker start l2s-db >nul || exit /b 1
)

set /a W=0
:waitdb
for /f %%S in ('docker inspect -f "{{.State.Health.Status}}" l2s-db 2^>nul') do set "HS=%%S"
if /i "!HS!"=="healthy" goto :import
set /a W+=1
if !W! GEQ 60 (
  echo restore: l2s-db not healthy
  exit /b 1
)
ping -n 2 127.0.0.1 >nul
goto :waitdb

:import
echo restore: copying dump into container ...
docker cp "!FILE!" l2s-db:/tmp/restore.sql.gz || exit /b 1
echo restore: importing into !DBNAME! ...
docker exec -e MYSQL_PWD=!DBPASS! l2s-db sh -c "gunzip -c /tmp/restore.sql.gz | mariadb -uroot !DBNAME! && rm -f /tmp/restore.sql.gz"
if errorlevel 1 (
  echo restore: import failed
  docker exec l2s-db rm -f /tmp/restore.sql.gz 2>nul
  exit /b 1
)

echo.
echo restore: OK. Start the server with: start.bat %PROFILE%
pause
exit /b 0
