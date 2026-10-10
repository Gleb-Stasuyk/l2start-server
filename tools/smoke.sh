#!/usr/bin/env bash
# Docker smoke test of the module platform (phase 1). Runs on Linux (CI) and in Git Bash on Windows.
#
#   bash tools/smoke.sh [--build]
#
# --build  builds l2start-server:dev (target runtime) and l2start-server:smoke (target smoke) first.
# Uses its own database volume (l2s-smoke-db-data), removed at the end; the normal l2s-db-data is never touched.
# It REPLACES the running l2s-* containers (same names/ports). Start the normal stack afterwards (start.bat solo).
#
# Scenarios:
#   1 stock     DisableAll=true                     -> healthy, startup counts == tools/smoke/baseline.txt
#   2 fixtures  profile solo + hello + 8 fixtures   -> healthy, each module in its expected state, counts = baseline + module data,
#                                                      breaker degrades fx-flaky, DB tables/migrations written
#   3 restart   same as 2, game restarted           -> healthy, no migration applied again
#   4 abort     profile online + fixtures            -> game exits with code 1, "FailurePolicy=abort"
#   5 online    profile online, fixtures off        -> healthy, hello DISABLED (off in [online])
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

start_game() { # start_game PROFILE DISABLE_ALL FIXTURES(on|off)
  export SMOKE_PROFILE=$1 SMOKE_DISABLE_ALL=$2 SMOKE_FIXTURES=$3
  "${C[@]}" up -d --no-build --force-recreate --no-deps game >/dev/null 2>&1 || "${C[@]}" up -d --no-build --force-recreate game
}

cleanup() {
  "${C[@]}" down --remove-orphans >/dev/null 2>&1 || true
  docker volume rm -f l2s-smoke-db-data l2s-smoke-game-logs >/dev/null 2>&1 || true
}

if [ "${1:-}" = "--build" ]; then
  say "building images ..."
  docker build -f docker/Dockerfile --target runtime -t l2start-server:dev "$ROOT" || exit 2
  docker build -f docker/Dockerfile --target smoke -t l2start-server:smoke "$ROOT" || exit 2
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

# ---- 2 fixtures, solo
say "-- 2 solo + fixtures"
start_game solo false on
if wait_game healthy 900; then ok "game healthy (FailurePolicy=continue)"; else ko "game healthy"; fi
sleep 15 # breaker window for fx-flaky
gamelog > "$OUT/2-solo.log"
L="$OUT/2-solo.log"
check "summary line" grep -qE 'Platform: profile=solo, 9 discovered: 3 running, 3 failed, 2 refused, 1 invalid, 0 disabled' "$L"
check "hello RUNNING" state_is hello RUNNING "$L"
check "fx-ok-dep RUNNING (sees hello classes)" state_is fx-ok-dep RUNNING "$L"
check "fx-ok-dep onEnable/onServerReady ran" has "FIXTURE fx-ok-dep ready" "$L"
check "fx-missing-dep REFUSED" state_is fx-missing-dep REFUSED "$L"
check "fx-broken-enable FAILED" state_is fx-broken-enable FAILED "$L"
check "fx-broken-enable task was unregistered" hasnt "FIXTURE fx-broken-enable task ran" "$L"
check "fx-depends-on-broken REFUSED" state_is fx-depends-on-broken REFUSED "$L"
check "fx-bad-migration FAILED" state_is fx-bad-migration FAILED "$L"
check "fx-npc-collision FAILED" state_is fx-npc-collision FAILED "$L"
check "fx-invalid INVALID" state_is fx-invalid INVALID "$L"
check "fx-flaky DEGRADED by circuit breaker" grep -qE 'Platform: module fx-flaky DEGRADED' "$L"
check "hello enabled log line" grep -qE 'Module\[hello\].*enabled: GreetOnLogin=true' "$L"
counts "$L" > "$OUT/2-counts.txt"
sed -E -e 's/^BuyListData: Loaded 616 /BuyListData: Loaded 617 /' -e 's/^MultisellData: Loaded 208 /MultisellData: Loaded 209 /' \
  -e 's/^SpawnData: 186 spawn/SpawnData: 187 spawn/' -e 's/^SpawnData: 34122 spawns/SpawnData: 34123 spawns/' "$OUT/baseline.txt" > "$OUT/2-expected.txt"
if diff "$OUT/2-expected.txt" "$OUT/2-counts.txt" > "$OUT/2-counts.diff"; then ok "counts = stock + hello/fx-ok-dep data (buylist, multisell, spawn)"; else ko "counts differ from stock + module data (see 2-counts.diff)"; cat "$OUT/2-counts.diff"; fi
check "module data folders logged" grep -qE 'Platform: npcs from modules: 1 folder' "$L"
check "migration table: hello V001 ok" test "$(sql "SELECT success FROM platform_module_migrations WHERE module_id='hello' AND version=1")" = 1
check "migration table: fx-bad-migration V001 failed" test "$(sql "SELECT success FROM platform_module_migrations WHERE module_id='fx-bad-migration' AND version=1")" = 0
check "table mod_hello_counter exists" test "$(sql "SHOW TABLES LIKE 'mod_hello_counter'")" = mod_hello_counter
check "platform_modules: hello RUNNING" test "$(sql "SELECT last_state FROM platform_modules WHERE module_id='hello'")" = RUNNING
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
check "clean shutdown hook ran" grep -qE 'Module\[hello\].*shutdown' "$OUT/3-restart.log"

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
check "hello DISABLED in online" state_is hello DISABLED "$OUT/5-online.log"
check "summary: 0 failed" grep -qE 'Platform: profile=online, 9 discovered: 0 running, 0 failed, 0 refused, 0 invalid, 9 disabled' "$OUT/5-online.log"

say "-- cleanup"
cleanup
say "== smoke result: $([ $fails = 0 ] && echo PASSED || echo "FAILED ($fails)")"
[ $fails = 0 ]
