#!/usr/bin/env bash
# Check a geodata folder against tools/geodata-manifest.txt (sha256 + size of every *.l2j).
# Usage: tools/check-geodata.sh /path/to/geodata   (exit 0 = all files present and identical)
set -euo pipefail
dir="${1:?usage: $0 <geodata folder>}"
manifest="$(dirname "$0")/geodata-manifest.txt"
bad=0; n=0
while read -r sum size name; do
  [[ -z "$sum" || "$sum" == \#* ]] && continue
  n=$((n+1)); f="$dir/$name"
  if [[ ! -f "$f" ]]; then echo "MISSING $name"; bad=$((bad+1)); continue; fi
  if [[ "$(stat -c %s "$f")" != "$size" ]]; then echo "SIZE    $name"; bad=$((bad+1)); continue; fi
  if [[ "$(sha256sum "$f" | cut -d' ' -f1)" != "$sum" ]]; then echo "SHA256  $name"; bad=$((bad+1)); fi
done < "$manifest"
extra=$(comm -13 <(grep -v '^#' "$manifest" | awk '{print $3}' | sort) <(cd "$dir" && ls *.l2j 2>/dev/null | sort) | wc -l)
echo "geodata: $n files in manifest, $bad bad, $extra extra *.l2j in $dir"
[[ $bad -eq 0 ]]
