L2Start local server (Interlude, single player)

1. Install Docker Desktop: https://www.docker.com/products/docker-desktop/ and start it.
2. Unpack this folder anywhere (path without non-latin letters is safest) and run install.bat.
3. Put L2Launcher.exe into your Interlude client folder, choose "Local", point it to this folder, press Play.
   Without the launcher: set system\l2.ini ServerAddr=127.0.0.1.

start.bat / stop.bat - start / stop. Your characters live in the Docker volume l2l-db-data.
Settings: settings.env, modules.env, overrides\game\config\*.ini (applied on restart). The launcher edits them for you.
Reset world: docker compose down -v  (deletes all characters!)
