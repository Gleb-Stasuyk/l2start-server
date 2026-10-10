#!/usr/bin/env bash
# Verify that core/ in the latest vendor commit is byte-identical to the upstream Mobius tree
# recorded in upstream.lock (manifest = sha256 of sorted "<git blob sha1> <path>" lines).
# Usage: tools/verify-upstream.sh [ref]   (default: latest "vendor:" commit reachable from HEAD)
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
ref="${1:-$(git log -1 --format=%H --grep='^vendor:' HEAD)}"
[ -n "$ref" ] || { echo "no vendor commit found"; exit 1; }
lock="$(git show "$ref:upstream.lock")"
want_sha=$(printf '%s\n' "$lock" | sed -n 's/^manifest_sha256=//p')
want_files=$(printf '%s\n' "$lock" | sed -n 's/^files=//p')
manifest=$(git -c core.quotepath=off ls-tree -r "$ref" -- core \
  | awk -F'\t' '{split($1,a," "); p=$2; sub(/^core\//,"",p); print a[3]" "p}' | LC_ALL=C sort)
files=$(printf '%s\n' "$manifest" | wc -l | tr -d ' ')
sha=$(printf '%s' "$manifest" | sha256sum | cut -c1-64)
echo "vendor commit : $(git log -1 --format='%h %s' "$ref")"
echo "upstream      : $(printf '%s\n' "$lock" | sed -n 's/^commit=//p') ($(printf '%s\n' "$lock" | sed -n 's/^commit_date=//p'))"
echo "files         : $files (lock: $want_files)"
echo "manifest      : $sha"
echo "lock manifest : $want_sha"
if [ "$sha" = "$want_sha" ] && [ "$files" = "$want_files" ]; then
  echo "OK: core/ is byte-identical to upstream"
else
  echo "FAIL: core/ in the vendor commit differs from upstream.lock"; exit 1
fi
