#!/usr/bin/env bash
# Check that core/ on HEAD differs from the vendor import only by marked platform hooks.
# Phase 0: no hooks exist yet, so any change in core/ must carry a "[HOOK:<id>]" marker in its
# added lines (per file) and be listed in docs/PLATFORM_HOOKS.md. Full per-hunk checks come in phase 1.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
base=$(git log -1 --format=%H --grep='^vendor:' HEAD)
[ -n "$base" ] || { echo "no vendor commit found"; exit 1; }
echo "vendor base: $(git log -1 --format='%h %s' "$base")"
changed=$(git -c core.quotepath=off diff --name-only "$base" HEAD -- core)
if [ -z "$changed" ]; then
  echo "OK: core/ is identical to the vendor import (0 changed files)"
  exit 0
fi
fail=0
while IFS= read -r f; do
  if git diff "$base" HEAD -- "$f" | grep -E '^\+' | grep -q '\[HOOK:'; then
    id=$(git diff "$base" HEAD -- "$f" | grep -oE '\[HOOK:[A-Za-z0-9_.-]+\]' | head -1)
    if grep -qF "${id}" docs/PLATFORM_HOOKS.md 2>/dev/null; then
      echo "hook   $f $id"
    else
      echo "UNLISTED $f $id (add it to docs/PLATFORM_HOOKS.md)"; fail=1
    fi
  else
    echo "UNMARKED $f (core change without [HOOK:id] marker)"; fail=1
  fi
done <<< "$changed"
[ "$fail" = 0 ] && echo "OK: all core changes are marked hooks" || { echo "FAIL"; exit 1; }
