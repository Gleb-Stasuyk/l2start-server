#!/bin/sh
# l2start-server container entrypoint: db-init | login | game
# Never edits the repository: the image holds a copy of core/dist, configs are patched inside the container.
set -e

ROLE="${1:-game}"
L2=/opt/l2
DB_HOST="${DB_HOST:-db}"
DB_PORT="${DB_PORT:-3306}"
DB_NAME="${DB_NAME:-l2start}"
DB_USER="${DB_USER:-root}"
DB_PASSWORD="${DB_PASSWORD:?DB_PASSWORD is not set}"
LOGIN_HOST="${LOGIN_HOST:-login}"
PUBLIC_HOST="${PUBLIC_HOST:-127.0.0.1}"
OVERRIDES="${L2_OVERRIDES:-$L2/overrides}"

log() { echo "[entrypoint/$ROLE] $*"; }
sql() { mariadb --skip-ssl -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" -p"$DB_PASSWORD" --default-character-set=utf8 "$@"; }

wait_db() {
  log "waiting for database $DB_HOST:$DB_PORT ..."
  i=0
  until sql -e "SELECT 1" >/dev/null 2>&1; do
    i=$((i + 1))
    [ "$i" -ge 120 ] && { log "database not reachable after 240s"; exit 1; }
    sleep 2
  done
  log "database is up"
}

# Import login + game SQL only into an EMPTY database (several stock SQL files DROP TABLE first).
db_init() {
  sql -e "CREATE DATABASE IF NOT EXISTS \`$DB_NAME\` CHARACTER SET utf8 COLLATE utf8_unicode_ci;"
  count=$(sql -N -B -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DB_NAME';")
  if [ "$count" -gt 0 ]; then
    log "database '$DB_NAME' already has $count tables - import skipped"
    return
  fi
  log "empty database '$DB_NAME' - importing stock schema ..."
  for group in login game; do
    n=0
    for f in $(ls "$L2/sql/$group"/*.sql | sort); do
      sql "$DB_NAME" < "$f" || { log "ERROR importing $f"; exit 1; }
      n=$((n + 1))
    done
    log "  $group: $n files imported"
  done
  log "schema installed: $(sql -N -B -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DB_NAME';") tables"
}

# set_ini FILE KEY VALUE : replace "Key = ..." (or append when the key is missing)
set_ini() {
  if grep -q "^$2[[:space:]]*=" "$1"; then
    esc=$(printf '%s' "$3" | sed 's/[&|\\]/\\&/g')
    sed -i "s|^$2[[:space:]]*=.*|$2 = $esc|" "$1"
  else
    printf '\n%s = %s\n' "$2" "$3" >> "$1"
  fi
}

# Apply profile overrides: $OVERRIDES/<login|game>/config/<File>.ini with "Key = Value" lines.
apply_overrides() {
  dir="$OVERRIDES/$1/config"
  [ -d "$dir" ] || return 0
  for of in "$dir"/*.ini "$dir"/Custom/*.ini; do  # Custom/<File>.ini -> stock config/Custom/<File>.ini
    [ -f "$of" ] || continue
    case "$(basename "$of")" in platform.local.ini|modules.local.ini) continue ;; esac  # see apply_platform_overrides
    sub=""; case "$of" in "$dir"/Custom/*) sub="Custom/" ;; esac
    target="$L2/$1/config/$sub$(basename "$of")"
    [ -f "$target" ] || { log "override $sub$(basename "$of"): no such stock file, skipped"; continue; }
    tr -d '\r' < "$of" | grep -E '^[[:space:]]*[A-Za-z0-9_.]+[[:space:]]*=' | while IFS= read -r line; do
      key=$(printf '%s' "$line" | sed -E 's/^[[:space:]]*([A-Za-z0-9_.]+)[[:space:]]*=.*/\1/')
      val=$(printf '%s' "$line" | sed -E 's/^[^=]*=[[:space:]]*//; s/[[:space:]]*$//')
      set_ini "$target" "$key" "$val"
      log "override $1/$sub$(basename "$of"): $key = $val"
    done
  done
}

# Platform files are COPIED, not merged: $OVERRIDES/game/config/{platform.local.ini,modules.local.ini}
# and $OVERRIDES/game/config/modules/<id>.ini (module settings, see docs/MODULES.md).
apply_platform_overrides() {
  src="$OVERRIDES/game/config"
  dst="$L2/game/config"
  for f in platform.local.ini modules.local.ini; do
    if [ -f "$src/$f" ]; then
      tr -d '\r' < "$src/$f" > "$dst/$f"
      log "platform override: config/$f"
    fi
  done
  if [ -d "$src/modules" ]; then
    mkdir -p "$dst/modules"
    for f in "$src/modules"/*.ini; do
      [ -f "$f" ] || continue
      tr -d '\r' < "$f" > "$dst/modules/$(basename "$f")"
      log "platform override: config/modules/$(basename "$f")"
    done
  fi
}

patch_common() { # $1 = login|game
  cfg="$L2/$1/config"
  url="jdbc:mysql://${DB_HOST}:${DB_PORT}/${DB_NAME}?useUnicode=true&characterEncoding=utf-8&allowPublicKeyRetrieval=true&useSSL=false&connectTimeout=10000&interactiveClient=true&sessionVariables=wait_timeout=600,interactive_timeout=600&autoReconnect=true"
  set_ini "$cfg/Database.ini" URL "$url"
  set_ini "$cfg/Database.ini" Login "$DB_USER"
  set_ini "$cfg/Database.ini" Password "$DB_PASSWORD"
  [ -f "$cfg/Interface.ini" ] && set_ini "$cfg/Interface.ini" EnableGUI False
  mkdir -p "$L2/$1/log"
}

# java.cfg from Mobius minus heap flags (heap comes from env)
java_cfg() { tr -d '\r' < "$1" | sed -E 's/-Xm[sx][0-9]+[kKmMgG]?//g'; }

case "$ROLE" in
  db-init)
    wait_db
    db_init
    ;;
  login)
    wait_db
    patch_common login
    S="$L2/login/config/Server.ini"
    set_ini "$S" LoginserverHostname 0.0.0.0   # clients (published as host port 2106)
    set_ini "$S" LoginHostname 0.0.0.0         # game server registration port 9014 (docker network only)
    apply_overrides login
    log "AutoCreateAccounts = $(grep -E '^AutoCreateAccounts' "$S" | sed 's/.*= *//')"
    cd "$L2/login"
    log "starting LoginServer (heap ${LOGIN_XMS:-128m}..${LOGIN_XMX:-256m})"
    # shellcheck disable=SC2046
    exec java $(java_cfg java.cfg) -Xms${LOGIN_XMS:-128m} -Xmx${LOGIN_XMX:-256m} -Djava.awt.headless=true -jar ../libs/LoginServer.jar
    ;;
  game)
    wait_db
    patch_common game
    S="$L2/game/config/Server.ini"
    set_ini "$S" LoginHost "$LOGIN_HOST"
    set_ini "$S" GameserverHostname 0.0.0.0
    apply_overrides game
    apply_platform_overrides
    log "platform profile: ${L2_PROFILE:-solo}"
    # The login server hands this address to the client. Docker Desktop NATs the client
    # (it appears from a 172.x / 192.168.65.x address), so everything maps to PUBLIC_HOST.
    cat > "$L2/game/config/ipconfig.xml" <<XML
<?xml version="1.0" encoding="UTF-8"?>
<!-- generated by docker/entrypoint.sh -->
<gameserver address="${PUBLIC_HOST}" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:noNamespaceSchemaLocation="../data/xsd/ipconfig.xsd">
	<define subnet="127.0.0.0/8" address="${PUBLIC_HOST}" />
</gameserver>
XML
    geo=$(ls "$L2/game/data/geodata" 2>/dev/null | grep -ciE '\.(l2j|dat)$' || true)
    log "geodata files: ${geo:-0}"
    cd "$L2/game"
    log "starting GameServer (heap ${GAME_XMS:-512m}..${GAME_XMX:-2g}, public host ${PUBLIC_HOST})"
    # shellcheck disable=SC2046
    exec java $(java_cfg java.cfg) -Xms${GAME_XMS:-512m} -Xmx${GAME_XMX:-2g} -Djava.awt.headless=true -jar ../libs/GameServer.jar
    ;;
  *)
    echo "usage: entrypoint.sh db-init|login|game"; exit 1 ;;
esac
