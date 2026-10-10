#!/usr/bin/env bash
# Check that core/ differs from the vendor import (last commit "vendor: ...") ONLY by marked platform hooks.
#
# Rules (docs/PLATFORM_HOOKS.md):
#   1. only modifications of existing core files: no new, deleted or renamed files;
#   2. no stock line is removed or changed: the diff may only ADD lines;
#   3. every added line carries "[HOOK:<id>]", or lies inside a block
#        // >>> [HOOK:<id>]   ...   // <<< [HOOK:<id>]
#   4. every hook id used in core/ is listed in docs/PLATFORM_HOOKS.md (row "| `<id>` | ...");
#   5. every id listed there is used in core/ (no stale rows).
#   6. root build.xml compiles for the same Java release as core/build.xml.
#
# Usage: tools/check-hooks.sh            compare vendor..HEAD
#        tools/check-hooks.sh --worktree compare vendor..working tree (before committing)
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
DOC=docs/PLATFORM_HOOKS.md
base=$(git log -1 --format=%H --grep='^vendor:' HEAD)
[ -n "$base" ] || { echo "no vendor commit found"; exit 1; }
echo "vendor base: $(git log -1 --format='%h %s' "$base")"
if [ "${1:-}" = "--worktree" ]; then range=("$base"); echo "comparing with the working tree"; else range=("$base" HEAD); fi

fail=0
bad() { echo "FAIL: $*"; fail=1; }

# Rule 1
while IFS=$'\t' read -r st path rest; do
  [ -z "$st" ] && continue
  case "$st" in
    M) ;;
    *) bad "core file status $st: $path${rest:+ -> $rest} (hooks may only modify existing stock files)" ;;
  esac
done < <(git -c core.quotepath=off diff --no-renames --name-status "${range[@]}" -- core; git -c core.quotepath=off diff -M --name-status "${range[@]}" -- core | grep -E '^R' || true)

used_file=$(mktemp); trap 'rm -f "$used_file"' EXIT
nfiles=0
while IFS= read -r f; do
  [ -z "$f" ] && continue
  nfiles=$((nfiles + 1))
  # Rules 2 and 3, one awk pass over a zero-context diff
  out=$(git -c core.quotepath=off diff --no-color --no-ext-diff -U0 "${range[@]}" -- "$f" | awk -v file="$f" '
    /^(diff --git|index |--- |\+\+\+ |new file|deleted file|old mode|new mode|\\ No newline)/ { next }
    /^@@/ { if (block != "") { print "FAIL: " file ": hook block [HOOK:" block "] not closed inside its hunk"; block = "" } ; match($0, /\+[0-9]+/); line = substr($0, RSTART + 1, RLENGTH - 1) - 1; next }
    /^-/ { print "FAIL: " file ": stock line removed or changed: " substr($0, 2); next }
    /^\+/ {
      line++
      text = substr($0, 2)
      if (match(text, /\[HOOK:[A-Za-z0-9_.-]+\]/)) {
        id = substr(text, RSTART + 6, RLENGTH - 7)
        print "ID " id " " file ":" line
        if (text ~ />>>[ \t]*\[HOOK:/) { if (block != "") print "FAIL: " file ":" line ": nested hook block"; block = id }
        else if (text ~ /<<<[ \t]*\[HOOK:/) { if (block != id) print "FAIL: " file ":" line ": <<< [HOOK:" id "] without matching >>>"; block = "" }
        next
      }
      if (block != "") next
      print "FAIL: " file ":" line ": added line without [HOOK:id]: " text
    }
    END { if (block != "") print "FAIL: " file ": hook block [HOOK:" block "] not closed" }
  ' | tr -d '\r')
  if grep -q '^FAIL' <<< "$out"; then grep '^FAIL' <<< "$out"; fail=1; fi
  grep '^ID ' <<< "$out" | cut -d' ' -f2- >> "$used_file" || true
done < <(git -c core.quotepath=off diff --no-renames --diff-filter=M --name-only "${range[@]}" -- core)

# Rules 4 and 5
listed=$(grep -oE '^\|[[:space:]]*`[A-Za-z0-9_.-]+`' "$DOC" 2>/dev/null | grep -oE '[A-Za-z0-9_.-]+' | sort -u || true)
used=$(cut -d' ' -f1 "$used_file" | sort -u)
for id in $used; do
  if grep -qxF "$id" <<< "$listed"; then
    echo "hook   $id  $(grep "^$id " "$used_file" | cut -d' ' -f2 | sort -u | tr '\n' ' ')"
  else
    bad "hook id $id is used in core/ but not listed in $DOC"
  fi
done
for id in $listed; do
  grep -qxF "$id" <<< "$used" || bad "hook id $id is listed in $DOC but not used in core/ (stale row)"
done

# Rule 6
core_rel=$(grep -oE 'source="[0-9]+"' core/build.xml | head -1 | grep -oE '[0-9]+')
root_rel=$(grep -oE 'name="java.release" value="[0-9]+"' build.xml 2>/dev/null | grep -oE '[0-9]+"$' | tr -d '"' || true)
if [ -n "$root_rel" ] && [ "$root_rel" != "$core_rel" ]; then
  bad "build.xml java.release=$root_rel but core/build.xml compiles for Java $core_rel"
fi

echo "core files changed: $nfiles, hook ids: $(wc -w <<< "$used")"
if [ "$fail" = 0 ]; then echo "OK: all core changes are marked, listed platform hooks"; else echo "FAIL"; exit 1; fi
