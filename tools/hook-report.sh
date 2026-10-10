#!/usr/bin/env bash
# Lists every [HOOK:<id>] marker in core/ with file and line (helps re-applying hooks after an upstream update).
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
printf '%-16s %s\n' ID LOCATION
git grep -n -I -E '\[HOOK:[A-Za-z0-9_.-]+\]' -- core | tr -d '\r' | sed -E 's/^([^:]+):([0-9]+):.*\[HOOK:([A-Za-z0-9_.-]+)\].*/\3 \1:\2/' | sort | while read -r id loc; do printf '%-16s %s\n' "$id" "$loc"; done
