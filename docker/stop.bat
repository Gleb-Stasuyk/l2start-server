@echo off
setlocal
cd /d "%~dp0"
rem l2start-server in Docker: stop (data stays in docker volume l2s-db-data)

echo Stopping l2start-server (the game server saves data on shutdown, may take up to 2 min) ...
docker compose --env-file profiles\solo.env stop
echo Done.

choice /c NLW /t 20 /d N /m "Start an old server again? N = no, L = l2-* (old server), W = lw-* (Living Worlds)"
if errorlevel 3 (
  docker start lw-db lw-login lw-game
  goto :end
)
if errorlevel 2 (
  docker start l2-db l2-login l2-game
  goto :end
)

:end
pause
