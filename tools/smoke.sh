#!/usr/bin/env bash
# Docker smoke test of the module platform and the real modules (phases 1-3). Runs on Linux (CI) and in Git Bash on Windows.
#
#   bash tools/smoke.sh [--build]
#
# --build  builds l2start-server:dev (target runtime) and l2start-server:smoke (target smoke) first.
# Uses its own database volume (l2s-smoke-db-data), removed at the end; the normal l2s-db-data is never touched.
# It REPLACES the running l2s-* containers (same names/ports). Start the normal stack afterwards (start.bat solo).
#
# Scenarios:
# The image always contains the real modules of modules/ (starter-kit, lw-fixes, economy-market: on in both profiles; bots-core,
# bots-brain-basic: solo only);
# the smoke image adds 9 test modules (platform/test/fixtures), switched by SMOKE_FIXTURES.
#   1 stock     DisableAll=true                     -> healthy, startup counts == tools/smoke/baseline.txt, no module data
#   2 fixtures  profile solo + 9 fixtures           -> healthy, each module in its expected state, counts = baseline + module data,
#                                                      fx-core verifies NPC/spawn/html/commands/i18n/db/config/scheduler/reload
#                                                      and the starting gear (starter-kit through hook P2-DATA-INITEQUIP),
#                                                      breaker degrades fx-flaky, DB tables/migrations written
#   3 restart   same as 2, game restarted           -> healthy, no migration applied again, shutdown hook ran, module data kept
#   4 abort     profile online + fixtures            -> game exits with code 1, "FailurePolicy=abort"
#   5 online    profile online, fixtures off        -> healthy, all 9 fixtures + bots-* DISABLED, starter-kit + lw-fixes RUNNING, counts == stock
#   6 solo      profile solo, fixtures off (= what players get) -> healthy, starter-kit + lw-fixes + bots-core + bots-brain-basic RUNNING, counts == stock,
#                                                      bots-core tables created, no bot enters the world without geodata
#   7 bots      solo, AutoCreate=20 AutoSpawn=10, RequireGeodata=false -> 20 bot characters (banned accounts, online=0,
#                                                      starting gear, personas, state), 10 in the world, logged out on shutdown;
#                                                      brain 'basic' runs: the bots walk, attack and kill monsters, 0 brain errors;
#                                                      after a restart (brain off, so they stand) the SAME 10 come back (restore) at the
#                                                      same places, nothing created twice
set -uo pipefail
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT/docker"
ENVF=profiles/solo.env
# shellcheck disable=SC1090
. "./$ENVF"
DBN=${L2S_DB_NAME:-l2start}
C=(docker compose --env-file "$ENVF" -f docker-compose.yml -f compose.smoke.yml)
BASE="$ROOT/tools/smoke/baseline.txt"
OUT=${SMOKE_OUT:-$ROOT/build/smoke}
mkdir -p "$OUT"
SUMMARY="$OUT/summary.txt"
: > "$SUMMARY"
fails=0

say() { echo "$*" | tee -a "$SUMMARY"; }
ok() { say "  PASS  $*"; }
ko() { say "  FAIL  $*"; fails=$((fails + 1)); }
check() { local d="$1"; shift; if "$@"; then ok "$d"; else ko "$d"; fi; }
sql() { docker exec l2s-db mariadb -uroot -p"$L2S_DB_PASSWORD" -N -B "$DBN" -e "$1" 2>/dev/null | tr -d '\r'; }
gamelog() { docker logs l2s-game 2>&1 | tr -d '\r'; }
has() { grep -qF -- "$1" "$2"; }
hasnt() { ! grep -qF -- "$1" "$2"; }
counts() { sed -E 's/^\[[^]]*\] //' "$1" | grep -iE 'loaded|spawn files parsed|spawns have been initialized' | grep -v 'Server loaded in' | grep -vE '^(Platform|Module\[)' | grep -v 'FIXTURE'; }
state_is() { grep -qE "Platform:   $1 [^ ]+ $2(:|\$)" "$3"; }

wait_game() { # wait_game healthy|exited TIMEOUT_S
  local want=$1 limit=$2 t=0 st
  while [ $t -lt "$limit" ]; do
    st=$(docker inspect -f '{{.State.Status}}/{{if .State.Health}}{{.State.Health.Status}}{{end}}' l2s-game 2>/dev/null || echo missing/)
    case "$want/$st" in
      healthy/running/healthy) return 0 ;;
      exited/exited/*) return 0 ;;
      healthy/exited/*) echo "game exited (code $(docker inspect -f '{{.State.ExitCode}}' l2s-game))"; return 1 ;;
    esac
    sleep 5; t=$((t + 5))
  done
  echo "timeout waiting for game ($want), last state $st"; return 1
}

start_game() { # start_game PROFILE DISABLE_ALL FIXTURES(on|off) [BOTS_CREATE BOTS_SPAWN [BRAIN]]
  export SMOKE_PROFILE=$1 SMOKE_DISABLE_ALL=$2 SMOKE_FIXTURES=$3 SMOKE_BOTS_CREATE=${4:-0} SMOKE_BOTS_SPAWN=${5:-0} SMOKE_BOTS_BRAIN=${6:-basic} SMOKE_BOTS_STATS=20s
  export SMOKE_BOTS_PRESENCE=${SMOKE_BOTS_PRESENCE:-director} SMOKE_BOTS_ANCHORS=${SMOKE_BOTS_ANCHORS:-}
  if [ "${4:-0}" != 0 ] || [ -n "$SMOKE_BOTS_ANCHORS" ]; then export SMOKE_BOTS_GEO=false SMOKE_BOTS_SEED=12345; else export SMOKE_BOTS_GEO=true SMOKE_BOTS_SEED=0; fi
  "${C[@]}" up -d --no-build --force-recreate --no-deps game >/dev/null 2>&1 || "${C[@]}" up -d --no-build --force-recreate game
}

cleanup() {
  "${C[@]}" down --remove-orphans >/dev/null 2>&1 || true
  docker volume rm -f l2s-smoke-db-data l2s-smoke-game-logs >/dev/null 2>&1 || true
}

if [ "${1:-}" = "--build" ]; then
  say "building images ..."
  # relative paths from the repo root (absolute /f/... paths would break docker.exe under Git Bash)
  (cd "$ROOT" && docker build -f docker/Dockerfile --target runtime -t l2start-server:dev .) || exit 2
  (cd "$ROOT" && docker build -f docker/Dockerfile --target smoke -t l2start-server:smoke .) || exit 2
fi
docker image inspect l2start-server:dev l2start-server:smoke >/dev/null 2>&1 || { echo "images missing: run with --build"; exit 2; }

say "== smoke $(date '+%Y-%m-%d %H:%M:%S %Z'), output in $OUT"
cleanup
"${C[@]}" up -d --no-build db >/dev/null 2>&1
"${C[@]}" up -d --no-build login || { ko "db/db-init/login start"; cleanup; exit 1; }

# ---- 1 stock baseline
say "-- 1 stock (DisableAll=true)"
start_game solo true off
if wait_game healthy 900; then ok "game healthy"; else ko "game healthy"; fi
gamelog > "$OUT/1-stock.log"
check "DisableAll logged" has "Platform: DisableAll = true" "$OUT/1-stock.log"
grep -vE '^#' "$BASE" > "$OUT/baseline.txt"
counts "$OUT/1-stock.log" > "$OUT/1-counts.txt"
if diff "$OUT/baseline.txt" "$OUT/1-counts.txt" > "$OUT/1-counts.diff"; then ok "startup counts identical to stock baseline ($(wc -l < "$OUT/baseline.txt") lines)"; else ko "startup counts differ from stock baseline (see 1-counts.diff)"; cat "$OUT/1-counts.diff"; fi
check "no platform tables created" test -z "$(sql "SHOW TABLES LIKE 'platform_%'")"
check "no module starting gear loaded" hasnt "initialEquipment from modules" "$OUT/1-stock.log"

# ---- 2 fixtures, solo
say "-- 2 solo + fixtures"
start_game solo false on
if wait_game healthy 900; then ok "game healthy (FailurePolicy=continue)"; else ko "game healthy"; fi
sleep 15 # breaker window for fx-flaky
gamelog > "$OUT/2-solo.log"
L="$OUT/2-solo.log"
check "summary line" grep -qE 'Platform: profile=solo, 14 discovered: 8 running, 3 failed, 2 refused, 1 invalid, 0 disabled' "$L"
check "fx-core RUNNING" state_is fx-core RUNNING "$L"
check "fx-core: NPC, NPC listener, spawn, html, voiced/admin/bypass commands, i18n, db" has "FIXTURE fx-core checks OK (npc, npc-listener, spawn, html, voiced, admin, bypass, i18n, db)" "$L"
check "fx-core: enabled with registrations and 1 new migration" grep -qE 'Platform: enabled fx-core 1\.0\.0 \([1-9][0-9]* registrations, 1 new migrations\)' "$L"
check "fx-core: config defaults.ini [solo] beats module.json" has "Label=defaults-solo (" "$L"
check "fx-core: config env beats files" has "Limit=7 (env L2_MOD_FX_CORE__LIMIT)" "$L"
check "fx-core: scheduler task ran" has "FIXTURE fx-core tick 3" "$L"
check "fx-core: //module reload path -> onConfigReload" has "FIXTURE fx-core config reloaded, Limit=7" "$L"
check "fx-core: db row written (n=1)" has "FIXTURE fx-core db n=1" "$L"
check "fx-ok-dep RUNNING" state_is fx-ok-dep RUNNING "$L"
check "fx-ok-dep: sees fx-core classes + service" has "service answer=42" "$L"
check "fx-ok-dep: its buylist 95010 loaded" has "FIXTURE fx-ok-dep ready, buylist 95010 loaded" "$L"
check "starter-kit RUNNING" state_is starter-kit RUNNING "$L"
check "lw-fixes RUNNING" state_is lw-fixes RUNNING "$L"
check "bots-core RUNNING" state_is bots-core RUNNING "$L"
check "bots-brain-basic RUNNING" state_is bots-brain-basic RUNNING "$L"
check "starter-kit: module starting gear folder read (hook P2-DATA-INITEQUIP)" grep -qE 'Platform: initialEquipment from modules: 1 folder' "$L"
check "starter-kit: classes 0/10/53 start with Long Sword/Mage Staff/Ubiquitous Axe" has "FIXTURE fx-core initial equipment first items 0=2,10=177,53=4221" "$L"
check "fx-missing-dep REFUSED" state_is fx-missing-dep REFUSED "$L"
check "fx-broken-enable FAILED" state_is fx-broken-enable FAILED "$L"
check "fx-broken-enable task was unregistered" hasnt "FIXTURE fx-broken-enable task ran" "$L"
check "fx-depends-on-broken REFUSED" state_is fx-depends-on-broken REFUSED "$L"
check "fx-bad-migration FAILED" state_is fx-bad-migration FAILED "$L"
check "fx-npc-collision FAILED" state_is fx-npc-collision FAILED "$L"
check "fx-invalid INVALID" state_is fx-invalid INVALID "$L"
check "fx-flaky DEGRADED by circuit breaker" grep -qE 'Platform: module fx-flaky DEGRADED' "$L"
# Lines that depend on rows the FIRST boot (scenario 1) wrote into the database, not on data files.
VOLATILE='^(GlobalVariablesManager|ClanHallAuctionManager|MonsterRace):'
counts "$L" | grep -vE "$VOLATILE" > "$OUT/2-counts.txt"
grep -vE "$VOLATILE" "$OUT/baseline.txt" | sed -E -e 's/^BuyListData: Loaded 616 /BuyListData: Loaded 617 /' -e 's/^MultisellData: Loaded 208 /MultisellData: Loaded 209 /' \
  -e 's/^SpawnData: 186 spawn/SpawnData: 187 spawn/' -e 's/^SpawnData: 34122 spawns/SpawnData: 34123 spawns/' > "$OUT/2-expected.txt"
if diff "$OUT/2-expected.txt" "$OUT/2-counts.txt" > "$OUT/2-counts.diff"; then ok "counts = stock + fx-core/fx-ok-dep data (buylist, multisell, spawn)"; else ko "counts differ from stock + module data (see 2-counts.diff)"; cat "$OUT/2-counts.diff"; fi
check "module data folders logged" grep -qE 'Platform: npcs from modules: 1 folder' "$L"
check "migration table: fx-core V001 ok" test "$(sql "SELECT success FROM platform_module_migrations WHERE module_id='fx-core' AND version=1")" = 1
check "migration table: fx-bad-migration V001 failed" test "$(sql "SELECT success FROM platform_module_migrations WHERE module_id='fx-bad-migration' AND version=1")" = 0
check "table mod_fx_core_counter exists" test "$(sql "SHOW TABLES LIKE 'mod_fx_core_counter'")" = mod_fx_core_counter
check "platform_modules: fx-core RUNNING" test "$(sql "SELECT last_state FROM platform_modules WHERE module_id='fx-core'")" = RUNNING
check "platform_modules: fx-flaky DEGRADED" test "$(sql "SELECT last_state FROM platform_modules WHERE module_id='fx-flaky'")" = DEGRADED
sql "SELECT module_id, last_version, last_state, LEFT(COALESCE(last_error,''),120) FROM platform_modules ORDER BY module_id" > "$OUT/2-platform_modules.txt"
MIG=$(sql "SELECT COUNT(*) FROM platform_module_migrations")

# ---- 3 restart: nothing is migrated twice
say "-- 3 restart"
docker restart -t 120 l2s-game >/dev/null
sleep 10
if wait_game healthy 900; then ok "game healthy after restart"; else ko "game healthy after restart"; fi
gamelog > "$OUT/3-restart.log"
check "no migration applied again" test "$(grep -c 'applied migration' "$OUT/3-restart.log")" = "$(grep -c 'applied migration' "$L")"
check "migration rows unchanged ($MIG)" test "$(sql "SELECT COUNT(*) FROM platform_module_migrations")" = "$MIG"
check "onShutdown hook ran" has "FIXTURE fx-core shutdown, ticks=" "$OUT/3-restart.log"
check "module data survived restart (n=2)" has "FIXTURE fx-core db n=2" "$OUT/3-restart.log"

# ---- 4 online + fixtures: fail fast
say "-- 4 online + fixtures (FailurePolicy=abort)"
start_game online false on
if wait_game exited 900; then ok "game stopped"; else ko "game stopped"; fi
gamelog > "$OUT/4-online-abort.log"
check "exit code 1" test "$(docker inspect -f '{{.State.ExitCode}}' l2s-game)" = 1
check "FailurePolicy=abort logged" grep -qE 'Platform: FailurePolicy=abort \(profile online\)' "$OUT/4-online-abort.log"
check "no client port opened" hasnt "GameServer: Started" "$OUT/4-online-abort.log"

# ---- 5 online, fixtures off
say "-- 5 online, fixtures off"
start_game online false off
if wait_game healthy 900; then ok "game healthy"; else ko "game healthy"; fi
gamelog > "$OUT/5-online.log"
check "fx-core DISABLED by switch" state_is fx-core DISABLED "$OUT/5-online.log"
check "summary: real modules running, fixtures + bots disabled" grep -qE 'Platform: profile=online, 14 discovered: 3 running, 0 failed, 0 refused, 0 invalid, 11 disabled' "$OUT/5-online.log"
check "bots-core DISABLED in online" state_is bots-core DISABLED "$OUT/5-online.log"
check "online: stock no-store zones kept" hasnt "stores anywhere:" "$OUT/5-online.log"
check "online: bot market off, only the log and index (economy-market)" grep -qE 'Module\[economy-market\]: market: .*market off \(log and index only\)' "$OUT/5-online.log"
check "bots-brain-basic DISABLED in online" state_is bots-brain-basic DISABLED "$OUT/5-online.log"
real_modules() { # real_modules LOG N
  check "$2: starter-kit RUNNING" state_is starter-kit RUNNING "$1"
  check "$2: lw-fixes RUNNING" state_is lw-fixes RUNNING "$1"
  check "$2: lw-fixes cursed weapon fix on" has "Module[lw-fixes]: enabled: CursedWeaponRefresh=true" "$1"
  check "$2: profile override AutoLoot = True" has "override game/Player.ini: AutoLoot = True" "$1"
  check "$2: profile override DisableTutorial = True" has "override game/Player.ini: DisableTutorial = True" "$1"
  check "$2: starting gear from starter-kit" grep -qE 'Platform: initialEquipment from modules: 1 folder' "$1"
  check "$2: no platform error for starter-kit / lw-fixes / economy-market" bash -c "! grep -qE 'Platform: module (starter-kit|lw-fixes|economy-market) ' \"\$0\"" "$1"
  check "$2: economy-market RUNNING" state_is economy-market RUNNING "$1"
  check "$2: market catalogue loaded" grep -qE 'Module\[economy-market\]: market: 2[0-9]{3} items, 0 indexed' "$1"
  counts "$1" | grep -vE "$VOLATILE" > "$OUT/$2-counts.txt"
  if grep -vE "$VOLATILE" "$OUT/baseline.txt" | diff - "$OUT/$2-counts.txt" > "$OUT/$2-counts.diff"; then ok "$2: startup counts == stock (real modules add no ids)"; else ko "$2: counts differ from stock (see $2-counts.diff)"; cat "$OUT/$2-counts.diff"; fi
}
real_modules "$OUT/5-online.log" 5

# ---- 6 solo, fixtures off: exactly what the player's server runs
say "-- 6 solo, real modules only"
start_game solo false off
if wait_game healthy 900; then ok "game healthy"; else ko "game healthy"; fi
gamelog > "$OUT/6-solo.log"
check "summary: 5 running, fixtures disabled" grep -qE 'Platform: profile=solo, 14 discovered: 5 running, 0 failed, 0 refused, 0 invalid, 9 disabled' "$OUT/6-solo.log"
check "bots-core RUNNING" state_is bots-core RUNNING "$OUT/6-solo.log"
check "bots-brain-basic RUNNING" state_is bots-brain-basic RUNNING "$OUT/6-solo.log"
check "brain 'basic' registered with bots-core and in use" grep -qE "Module\[bots-core\]: brain 'basic' registered \(in use\)" "$OUT/6-solo.log"
check "bots-brain-basic: hunting grounds loaded" grep -qE "Module\[bots-brain-basic\]: brain 'basic' registered: [0-9]+ hunting grounds around 5 starting villages" "$OUT/6-solo.log"
check "runtime started" grep -qE 'Module\[bots-core\]: runtime: [0-9]+ shards, wave 100 ms, budget 25 ms' "$OUT/6-solo.log"
check "bots-core ready, no geodata in the smoke image" grep -qE 'Module\[bots-core\]: ready: 0 bots known .*geodata in starting villages: NO' "$OUT/6-solo.log"
check "bots-core: tables created" test "$(sql "SHOW TABLES LIKE 'mod_bots_core_%'" | sort | tr '\n' ' ')" = "mod_bots_core_account mod_bots_core_identity mod_bots_core_persona mod_bots_core_progress mod_bots_core_state "
check "presence director on by default (whole world catalogue)" grep -qE 'Module\[bots-core\]: presence director: 16 towns, [0-9]{3,} hunting grounds, [0-9]+ armour sets, [0-9]+ weapons' "$OUT/6-solo.log"
check "presence: director, no anchors" grep -qE 'Module\[bots-core\]: presence: director \(0 old in-world marks cleared, anchors 0\)' "$OUT/6-solo.log"
check "economy-market: stores anywhere in solo (stock no-store zones off)" grep -qE 'Module\[economy-market\]: stores anywhere: [1-9][0-9]* no-store zones off' "$OUT/6-solo.log"
check "economy-market: bot market on in solo (no store places here: StoresPerTown Giran:0 until scenario 8)" grep -qE 'Module\[economy-market\]: market: .*market on, stores \{\}' "$OUT/6-solo.log"
check "economy-market: tables created" test "$(sql "SHOW TABLES LIKE 'mod_economy_market_%'" | sort | tr '\n' ' ')" = "mod_economy_market_flow mod_economy_market_hunter mod_economy_market_player_day mod_economy_market_price_index mod_economy_market_resale mod_economy_market_shopper mod_economy_market_spot mod_economy_market_store mod_economy_market_store_item mod_economy_market_trade_log mod_economy_market_wallet "
check "bots-core: no bot created by default" test "$(sql "SELECT COUNT(*) FROM mod_bots_core_identity")" = 0
real_modules "$OUT/6-solo.log" 6
check "platform_modules: starter-kit / lw-fixes RUNNING" test "$(sql "SELECT GROUP_CONCAT(last_state ORDER BY module_id) FROM platform_modules WHERE module_id IN ('lw-fixes','starter-kit')")" = "RUNNING,RUNNING"

# ---- 7 bots: create + enter the world without a client (smoke image has no geodata -> RequireGeodata=false)
say "-- 7 bots (AutoCreate=20, AutoSpawn=10, Presence=manual)"
export SMOKE_BOTS_PRESENCE=manual
BOTCHARS="SELECT char_id FROM mod_bots_core_identity"
wait_log() { # wait_log REGEX TIMEOUT_S
  # no grep -q here: with pipefail, grep -q quitting early kills tr with SIGPIPE and the wait reads "not found"
  local t=0; while [ $t -lt "$2" ]; do gamelog | grep -E "$1" >/dev/null && return 0; sleep 3; t=$((t + 3)); done; return 1
}
start_game solo false off 20 10
if wait_game healthy 900; then ok "game healthy"; else ko "game healthy"; fi
check "autostart: 20 created, 10 in the world" wait_log 'Module\[bots-core\]: autostart: created 20, entered 10, in world 10' 180
sleep 3
gamelog > "$OUT/7-bots.log"
check "no bots-core errors" bash -c "! grep -qE 'Module\[bots-(core|brain-basic)\]: .*(ERROR|cannot)|Platform: module bots-(core|brain-basic) ' \"\$0\"" "$OUT/7-bots.log"
# brain 'basic' (step 3d): within a few minutes the bots leave town, walk to their hunting grounds and fight
check "brain: bots move" wait_log 'BOTS world=10 ticking=10 .* moves=[1-9]' 120
check "brain: bots attack monsters" wait_log 'BOTS world=10 ticking=10 .* attacks=[1-9]' 360
check "brain: bots kill monsters" wait_log 'BOTS world=10 ticking=10 .* kills=[1-9]' 300
gamelog > "$OUT/7-bots.log"
grep -E 'BOTS world=' "$OUT/7-bots.log" | tail -3 | tee -a "$SUMMARY" >/dev/null
check "bots run, not walk (walking=0)" bash -c "grep -E 'BOTS world=' \"\$0\" | tail -1 | grep -qE ' walking=0([^0-9]|\$)'" "$OUT/7-bots.log"
check "brain: 0 brain errors, 0 safe-idle" bash -c "grep -E 'BOTS world=' \"\$0\" | tail -1 | grep -qE ' errors=0 safeIdle=0 '" "$OUT/7-bots.log"
check "brain: no brain failure logged" hasnt "brain of " "$OUT/7-bots.log"
check "20 bot identities" test "$(sql "SELECT COUNT(*) FROM mod_bots_core_identity WHERE status='ACTIVE'")" = 20
check "20 bot characters in characters, one per account" test "$(sql "SELECT COUNT(DISTINCT account_name) FROM characters WHERE charId IN ($BOTCHARS)")" = 20
check "all bot accounts banned (accessLevel=-1)" test "$(sql "SELECT COUNT(*) FROM accounts a JOIN mod_bots_core_account m ON m.account_name=a.login WHERE a.accessLevel=-1")" = 20
check "bots hidden: characters.online=0" test "$(sql "SELECT COUNT(*) FROM characters WHERE online<>0 AND charId IN ($BOTCHARS)")" = 0
check "bots level 1-5 (they fight now), starting classes" test "$(sql "SELECT COUNT(*) FROM characters WHERE level BETWEEN 1 AND 5 AND classid IN (0,10,18,25,31,38,44,49,53) AND charId IN ($BOTCHARS)")" = 20
check "bots have their starting gear (starter-kit)" test "$(sql "SELECT COUNT(DISTINCT owner_id) FROM items WHERE loc='PAPERDOLL' AND owner_id IN ($BOTCHARS)")" = 20
check "10 bots logged in" test "$(sql "SELECT COUNT(*) FROM mod_bots_core_identity WHERE last_login > 0")" = 10
check "20 personas, 20 state rows, 10 marked in the world" test "$(sql "SELECT COUNT(*) FROM mod_bots_core_persona"),$(sql "SELECT COUNT(*) FROM mod_bots_core_state"),$(sql "SELECT COUNT(*) FROM mod_bots_core_state WHERE in_world=1")" = "20,20,10"
check "personas: approved time zones, name = character name" test "$(sql "SELECT COUNT(*) FROM mod_bots_core_persona p JOIN mod_bots_core_identity i ON i.bot_id=p.bot_id WHERE p.data LIKE CONCAT('%\"name\":\"', i.char_name, '\"%') AND (p.data LIKE '%Europe/Moscow%' OR p.data LIKE '%Europe/Kaliningrad%' OR p.data LIKE '%Europe/Samara%' OR p.data LIKE '%Asia/Yekaterinburg%')")" = 20
check "personas: look = character look" test "$(sql "SELECT COUNT(*) FROM mod_bots_core_persona p JOIN mod_bots_core_identity i ON i.bot_id=p.bot_id JOIN characters c ON c.charId=i.char_id WHERE p.data LIKE CONCAT('%\"face\":', c.face, ',\"hairStyle\":', c.hairStyle, ',\"hairColor\":', c.hairColor, ',%') AND p.data LIKE CONCAT('%\"female\":', IF(c.sex=1,'true','false'), '%')")" = 20
INWORLD="SELECT i.char_name FROM mod_bots_core_identity i JOIN mod_bots_core_state s ON s.bot_id=i.bot_id WHERE s.in_world=1 ORDER BY 1"
sql "$INWORLD" > "$OUT/7-inworld-before.txt"
sql "SELECT i.char_name, i.class_id, c.x, c.y, c.z, c.online, a.accessLevel FROM mod_bots_core_identity i JOIN characters c ON c.charId=i.char_id JOIN accounts a ON a.login=i.account_name ORDER BY i.bot_id" > "$OUT/7-bots.txt"
POS="SELECT i.char_name, c.x, c.y, c.z FROM mod_bots_core_identity i JOIN characters c ON c.charId=i.char_id JOIN mod_bots_core_state s ON s.bot_id=i.bot_id WHERE s.in_world=1 AND c.curHp > 0 ORDER BY 1"
docker stop -t 120 l2s-game >/dev/null
docker logs l2s-game 2>&1 | tr -d '\r' > "$OUT/7-bots-stop.log"
check "clean shutdown: the 10 bots logged out" grep -qE 'Module\[bots-core\]: logged out 10 bots \((server shutdown|module stopped)\)' "$OUT/7-bots-stop.log"
sql "$POS" > "$OUT/7-pos-before.txt"
# restart with brain "none": restored bots stand still, so their places can be compared at the next stop
start_game solo false off 20 10 none
sleep 10
if wait_game healthy 900; then ok "game healthy after restart"; else ko "game healthy after restart"; fi
check "after restart: the 10 bots come back (restore)" wait_log 'Module\[bots-core\]: restore: 10 of 10 bots back in the world' 180
check "after restart: none created, none extra" wait_log 'Module\[bots-core\]: autostart: created 0, entered 0, in world 10' 60
gamelog > "$OUT/7-bots-restart.log"
check "bots logged out on shutdown (10)" test "$(sql "SELECT COUNT(*) FROM mod_bots_core_identity WHERE last_logout > 0")" = 10
check "still 20 bots, 20 accounts" test "$(sql "SELECT COUNT(*) FROM mod_bots_core_identity"),$(sql "SELECT COUNT(*) FROM mod_bots_core_account")" = "20,20"
check "ready line sees 20 bots, 10 to restore" grep -qE 'Module\[bots-core\]: ready: 20 bots known \(0 retired, 0 given a persona\), to restore 10' "$OUT/7-bots-restart.log"
sql "$INWORLD" > "$OUT/7-inworld-after.txt"
check "the same 10 bots are in the world after the restart" diff -q "$OUT/7-inworld-before.txt" "$OUT/7-inworld-after.txt"
docker stop -t 120 l2s-game >/dev/null
sql "$POS" > "$OUT/7-pos-after.txt"
# a bot that was dead at the 1st stop goes to town after the restart: compare the ones alive at the 1st stop
cut -f1 "$OUT/7-pos-before.txt" > "$OUT/7-pos-names.txt"
grep -F -w -f "$OUT/7-pos-names.txt" "$OUT/7-pos-after.txt" > "$OUT/7-pos-after-same.txt"
check "same places: positions saved at the 2nd stop == positions at the 1st stop (>= 8 of 10 bots alive)" bash -c "diff -q \"\$0\" \"\$1\" && test \$(wc -l < \"\$0\") -ge 8" "$OUT/7-pos-before.txt" "$OUT/7-pos-after-same.txt"

# ---- 8 presence director: two test players (Anchors) - one in Giran, one on a level 39-40 ground (Field of Silence)
say "-- 8 presence director (Anchors: Giran + Field of Silence)"
docker stop -t 120 l2s-game >/dev/null 2>&1 || true
export SMOKE_BOTS_PRESENCE=director SMOKE_BOTS_ANCHORS="82800;148500;-3450,88659;189101;-3610" SMOKE_MARKET_SELFTEST=true SMOKE_MARKET_STORES=Giran:60
start_game solo false off
if wait_game healthy 900; then ok "game healthy"; else ko "game healthy"; fi
check "director: anchors 2, old in-world marks cleared" wait_log 'Module\[bots-core\]: presence: director \([0-9]+ old in-world marks cleared, anchors 2\)' 300
check "director: 2 players in 2 clusters, bots brought in" wait_log 'DIRECTOR players=2 clusters=2 .* materialized=([4-9]|[1-9][0-9])' 300
check "director bots hunt (attacks)" wait_log 'BOTS world=[1-9][0-9]* .* attacks=[1-9]' 300
gamelog > "$OUT/8-director.log"
grep -E 'DIRECTOR players=|BOTS world=' "$OUT/8-director.log" | tail -4 | tee -a "$SUMMARY" >/dev/null
# "slow call" = a timing warning of the platform (a materialize on a slow CI runner), not an error;
# "cannot tell" = the town NPC reachability summary ("N cannot tell" without geodata), not an error
check "director: no bots-core errors" bash -c "! grep -E 'Module\[bots-(core|brain-basic)\]: .*(ERROR|cannot)|Platform: module bots-(core|brain-basic) |brain of ' \"\$0\" | grep -Eqv 'slow call|cannot tell'" "$OUT/8-director.log"
# in the job log too (CI artifacts need a login): what the director and the runtime said, and any error line
say "   last director/runtime lines:"; grep -E 'DIRECTOR players=|BOTS world=|presence: director|overload:' "$OUT/8-director.log" | tail -6 | cut -c1-400
say "   error lines (first 15):"; grep -E 'Module\[bots-(core|brain-basic)\]: .*(ERROR|cannot)|Platform: module bots-(core|brain-basic) |brain of |SEVERE|Exception' "$OUT/8-director.log" | grep -Ev 'slow call|cannot tell' | head -15 | cut -c1-400
# economy-market 5b-5f: 60 static bot traders at generated Giran places (polygon), crafters at the Adventure Guildsman, the 4UDO service trader, dwarf crafters
check "traders: 60 store places generated in Giran (kept in the database)" wait_log 'Module\[economy-market\]: traders: 60 new store places generated in Giran' 240
check "traders: the first store opened in Giran" wait_log 'Module\[economy-market\]: trader .* opened ' 240
t8=0; while [ $t8 -lt 240 ] && [ "$(gamelog | grep -cE 'Module\[economy-market\]: trader .* opened ')" -lt 58 ]; do sleep 3; t8=$((t8 + 3)); done
check "traders: >= 55 stores opened at the start without waiting for players" test "$(gamelog | grep -cE 'Module\[economy-market\]: trader .* opened (SELL|PACKAGE|BUY|CRAFT|SERVICE) ')" -ge 55
check "traders: buy stores among them" wait_log 'Module\[economy-market\]: trader .* opened BUY ' 30
check "traders: dwarf crafters (MANUFACTURE stores)" wait_log 'Module\[economy-market\]: trader .* opened CRAFT ' 30
check "traders: 4UDO sells shots at the NPC price at 82703,147932" wait_log 'Module\[economy-market\]: trader 4UDO opened SERVICE .* at giran-4udo \(6 lines\)' 30
check "SELFTEST: a sale from a bot sell store, no duplicated goods" wait_log 'Module\[economy-market\]: SELFTEST sell deal ok' 120
check "SELFTEST: a sale to a bot buy store, no duplicated goods" wait_log 'Module\[economy-market\]: SELFTEST buy deal ok' 30
check "SELFTEST: paying with all adena works (hook P5-STORE-ADENA)" wait_log 'Module\[economy-market\]: SELFTEST all-adena deal (ok|skipped)' 30
check "traders: 60 places in the database" test "$(sql "SELECT COUNT(*) FROM mod_economy_market_spot WHERE town='Giran'")" = 60
check "traders: places at least 60 apart" test "$(sql "SELECT COUNT(*) FROM mod_economy_market_spot a JOIN mod_economy_market_spot b ON a.town=b.town AND a.spot<b.spot WHERE (a.x-b.x)*(a.x-b.x)+(a.y-b.y)*(a.y-b.y) < 3600")" = 0
check "traders: all non-crafter places inside Gleb's market polygon (82194..82725, 147879..149330)" test "$(sql "SELECT COUNT(*) FROM mod_economy_market_spot WHERE town='Giran' AND kind<>'CRAFT' AND NOT (x BETWEEN 82194 AND 82725 AND y BETWEEN 147879 AND 149330)")" = 0
check "traders: no place on 4UDO (82703,147932)" test "$(sql "SELECT COUNT(*) FROM mod_economy_market_spot WHERE town='Giran' AND (x-82703)*(x-82703)+(y-147932)*(y-147932) < 5625")" = 0
check "traders: crafters sit around the Adventure Guildsman (82840,147796), not on him" test "$(sql "SELECT COUNT(*) FROM mod_economy_market_spot WHERE town='Giran' AND kind='CRAFT' AND (x-82840)*(x-82840)+(y-147796)*(y-147796) BETWEEN 160*160 AND 430*430")" -ge 4
say "   places: $(sql "SELECT kind='CRAFT', COUNT(*), MIN(x), MAX(x), MIN(y), MAX(y) FROM mod_economy_market_spot WHERE town='Giran' GROUP BY kind='CRAFT'" | tr '\t\n' ' ;')"
# 5f: bot ads in the trade chat (SelfTest: also without a real player)
check "trade chat: bot traders write WTS/WTB ads" wait_log 'Module\[economy-market\]: trade chat ad: ' 150
gamelog | grep -E 'trade chat ad: ' | head -4 | cut -c1-200 | tee -a "$SUMMARY"
sleep 20
gamelog > "$OUT/8-traders.log"
say "   trader lines:"; grep -E 'Module\[economy-market\]: (trader .* opened|spot giran-|traders: |SELFTEST)' "$OUT/8-traders.log" | head -12 | cut -c1-300
check "traders: static (nobody left except a sold-out store that comes back)" bash -c "! grep -E 'Module\[economy-market\]: trader .* left ' \"\$0\" | grep -qv 'sold out'" "$OUT/8-traders.log"
check "traders: geodata height of the 4UDO place logged" grep -qE 'Module\[economy-market\]: spot giran-4udo \(4UDO\) at 82703,147932' "$OUT/8-traders.log"
check "traders: no economy-market errors" bash -c "! grep -E 'Module\[economy-market\]: .*(failed|could not|FAILED)|Platform: module economy-market[: ]' \"\$0\" | grep -qv 'slow call'" "$OUT/8-traders.log"
say "   economy-market error lines (first 20):"; grep -v 'slow call' "$OUT/8-traders.log" | grep -A8 -E 'Module\[economy-market\]: .*(failed|could not|FAILED)|Platform: module economy-market[: ]' | head -20 | cut -c1-300
say "   trader bag items while they stand: $(sql "SELECT COUNT(*) FROM items WHERE loc='INVENTORY' AND owner_id IN (SELECT char_id FROM mod_bots_core_identity WHERE status='TRADER')")"
TRADERS="SELECT char_id FROM mod_bots_core_identity WHERE status='TRADER'"
check "traders: personas kept per place (>= 55, no progress rows)" test "$(sql "SELECT COUNT(*) FROM mod_bots_core_identity i WHERE status='TRADER' AND NOT EXISTS (SELECT 1 FROM mod_bots_core_progress g WHERE g.bot_id=i.bot_id)")" -ge 55
docker stop -t 120 l2s-game >/dev/null
docker logs l2s-game 2>&1 | tr -d '\r' > "$OUT/8-director-stop.log"
check "director: bots logged out on shutdown, nobody marked to come back" test "$(sql "SELECT COUNT(*) FROM mod_bots_core_state WHERE in_world<>0")" = 0
sleep 1
check "traders: stores on paper after the stop (>= 10 places with lines)" test "$(sql "SELECT COUNT(DISTINCT s.bot_id) FROM mod_economy_market_store s JOIN mod_economy_market_store_item i ON i.bot_id=s.bot_id WHERE s.town='Giran'")" -ge 10
check "traders: no goods left in trader bags (items exist only while they stand)" test "$(sql "SELECT COUNT(*) FROM items WHERE loc='INVENTORY' AND owner_id IN ($TRADERS)")" = 0
say "   trader bag items left (item_id count): $(sql "SELECT item_id, COUNT(*) FROM items WHERE loc='INVENTORY' AND owner_id IN ($TRADERS) GROUP BY item_id" | tr '\t\n' ':,')"
check "traders: no stock offline-trade rows of bots" test "$(sql "SELECT (SELECT COUNT(*) FROM character_offline_trade WHERE charId IN (SELECT char_id FROM mod_bots_core_identity)) + (SELECT COUNT(*) FROM character_offline_trade_items WHERE charId IN (SELECT char_id FROM mod_bots_core_identity))")" = 0
check "traders: the 2 self-test deals in the trade log" test "$(sql "SELECT COUNT(*) FROM mod_economy_market_trade_log WHERE seller_bot=1 AND buyer_bot=1")" -ge 2
check "traders: goods destroyed in the world on close (no stock save of the bag first)" bash -c "! grep -qE 'Module\[economy-market\]: trader .* bag rows still in the database' \"\$0\"" "$OUT/8-director-stop.log"
check "traders: all stores closed on the stop, in time (before bots-core stops)" bash -c "grep -qE 'Module\[economy-market\]: traders: [0-9]+ stores closed \(server stop\)' \"\$0\" && ! grep -qE 'Platform: module economy-market did not finish onShutdown' \"\$0\"" "$OUT/8-director-stop.log"
grep -E 'Module\[economy-market\]: (traders: [0-9]+ stores closed|trader .* left )|Platform: module economy-market (did not|failed)' "$OUT/8-director-stop.log" | head -6 | cut -c1-250 | tee -a "$SUMMARY"
BOTS8="SELECT i.char_id FROM mod_bots_core_identity i JOIN mod_bots_core_progress g ON g.bot_id=i.bot_id"
sql "SELECT i.char_name, c.level, c.classid, g.level_base, (SELECT COUNT(*) FROM items it WHERE it.owner_id=c.charId AND it.loc='PAPERDOLL') FROM mod_bots_core_identity i JOIN mod_bots_core_progress g ON g.bot_id=i.bot_id JOIN characters c ON c.charId=i.char_id ORDER BY i.bot_id" > "$OUT/8-bots.txt"
cat "$OUT/8-bots.txt" | tee -a "$SUMMARY" >/dev/null
check "director: >= 4 personas got a level of their zone (>= 30)" test "$(sql "SELECT COUNT(*) FROM characters WHERE level >= 30 AND charId IN ($BOTS8)")" -ge 4
check "director: level >= 20 = a profession (no starting class)" test "$(sql "SELECT COUNT(*) FROM characters WHERE level >= 20 AND classid IN (0,10,18,25,31,38,44,49,53) AND charId IN ($BOTS8)")" = 0
check "director: level >= 20 bots wear zone gear (>= 4 items)" test "$(sql "SELECT COUNT(*) FROM characters c WHERE c.level >= 20 AND c.charId IN ($BOTS8) AND (SELECT COUNT(*) FROM items it WHERE it.owner_id=c.charId AND it.loc='PAPERDOLL') < 4")" = 0
check "director: loot vanished (nothing in bags but arrows)" test "$(sql "SELECT COUNT(*) FROM items WHERE loc='INVENTORY' AND item_id NOT IN (17,1341,1342,1343,1344,1345) AND owner_id IN ($BOTS8)")" = 0
say "   bag items left (item_id count): $(sql "SELECT item_id, COUNT(*) FROM items WHERE loc='INVENTORY' AND item_id NOT IN (17,1341,1342,1343,1344,1345) AND owner_id IN ($BOTS8) GROUP BY item_id" | tr '\t\n' ':,')"
check "director: bots hidden (online=0)" test "$(sql "SELECT COUNT(*) FROM characters WHERE online<>0 AND charId IN ($BOTS8)")" = 0
unset SMOKE_BOTS_ANCHORS SMOKE_MARKET_SELFTEST SMOKE_MARKET_STORES

say "-- cleanup"
cleanup
say "== smoke result: $([ $fails = 0 ] && echo PASSED || echo "FAILED ($fails)")"
[ $fails = 0 ]
