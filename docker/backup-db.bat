@echo off
setlocal EnableDelayedExpansion
cd /d "%~dp0"
rem Dump DB from l2s-db to ..\backups\l2s-YYYYMMDD-HHMM.sql.gz, keep last 10.
rem Usage: backup-db.bat [solo^|online]

set "PROFILE=%~1"
if "%PROFILE%"=="" set "PROFILE=solo"
if not exist "profiles\%PROFILE%.env" (
  echo backup-db: unknown profile "%PROFILE%"
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
  echo backup-db: L2S_DB_PASSWORD not set
  exit /b 1
)

docker inspect l2s-db >nul 2>&1
if errorlevel 1 (
  echo backup-db: no l2s-db yet, skip backup
  exit /b 0
)

for /f %%S in ('docker inspect -f "{{.State.Status}}" l2s-db 2^>nul') do set "ST=%%S"
if /i not "!ST!"=="running" (
  echo backup-db: starting l2s-db ...
  docker start l2s-db >nul || exit /b 1
)

set /a W=0
:waitdb
for /f %%S in ('docker inspect -f "{{.State.Health.Status}}" l2s-db 2^>nul') do set "HS=%%S"
if /i "!HS!"=="healthy" goto :dump
set /a W+=1
if !W! GEQ 60 (
  echo backup-db: l2s-db not healthy
  exit /b 1
)
ping -n 2 127.0.0.1 >nul
goto :waitdb

:dump
set "BACKUPS=%~dp0..\backups"
if not exist "%BACKUPS%" mkdir "%BACKUPS%"
for /f %%I in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmm"') do set "TS=%%I"
set "OUT=%BACKUPS%\l2s-!TS!.sql.gz"
echo backup-db: dumping !DBNAME! to !OUT! ...
docker exec -e MYSQL_PWD=!DBPASS! l2s-db sh -c "mariadb-dump -uroot --single-transaction --routines --triggers !DBNAME! | gzip -c" > "!OUT!"
if errorlevel 1 (
  echo backup-db: mariadb-dump failed
  if exist "!OUT!" del /f /q "!OUT!"
  exit /b 1
)
set "SZ=0"
for %%A in ("!OUT!") do set "SZ=%%~zA"
if !SZ! LSS 100 (
  echo backup-db: dump too small
  del /f /q "!OUT!"
  exit /b 1
)
echo backup-db: ok !SZ! bytes

set /a N=0
for /f "delims=" %%F in ('dir /b /o-d "%BACKUPS%\l2s-*.sql.gz" 2^>nul') do (
  set /a N+=1
  if !N! GTR 10 (
    echo backup-db: remove old %%F
    del /f /q "%BACKUPS%\%%F"
  )
)
exit /b 0
