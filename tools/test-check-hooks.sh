#!/usr/bin/env bash
# Self-test of tools/check-hooks.sh on throw-away git repositories.
set -uo pipefail
here=$(cd "$(dirname "$0")" && pwd)
checker="$here/check-hooks.sh"
pass=0; failed=0
work=$(mktemp -d); trap 'rm -rf "$work"' EXIT

setup() {
  rm -rf "$work/r"; mkdir -p "$work/r/core/java" "$work/r/docs"; cd "$work/r"
  git init -q; git config user.email t@t; git config user.name t; git config core.autocrlf false
  printf 'class A {\n  void a() {\n    x();\n  }\n}\n' > core/java/A.java
  printf 'class B {}\n' > core/java/B.java
  printf '<javac source="27" target="27"/>\n' > core/build.xml
  printf '| Id | File |\n|---|---|\n' > docs/PLATFORM_HOOKS.md
  git add -A; git commit -qm "vendor: test"
}
listed() { for id in "$@"; do printf '| `%s` | x |\n' "$id" >> docs/PLATFORM_HOOKS.md; done; }
commit() { git add -A; git commit -qm change; }
expect() { # expect ok|fail "name"
  out=$(bash "$checker" 2>&1); rc=$?
  if { [ "$1" = ok ] && [ $rc = 0 ]; } || { [ "$1" = fail ] && [ $rc != 0 ]; }; then pass=$((pass + 1)); echo "PASS  $2"
  else failed=$((failed + 1)); echo "FAIL  $2 (rc=$rc)"; echo "$out" | sed 's/^/      /'; fi
}

setup; expect ok "1 untouched core"
setup; sed -i 's/^    x();/    x();\n    H.a(); \/\/ [HOOK:T1]/' core/java/A.java; listed T1; commit; expect ok "2 one-line marked, listed hook"
setup; sed -i 's/^    x();/    x();\n    y();/' core/java/A.java; commit; expect fail "3 unmarked added line"
setup; sed -i 's/^    x();/    z(); \/\/ [HOOK:T1]/' core/java/A.java; listed T1; commit; expect fail "4 changed stock line"
setup; printf 'class C {}\n' > core/java/C.java; commit; expect fail "5 new core file"
setup; git rm -q core/java/B.java; commit; expect fail "6 deleted core file"
setup; sed -i 's/^    x();/    x();\n    H.a(); \/\/ [HOOK:T1]/' core/java/A.java; commit; expect fail "7 hook id not listed"
setup; listed T9; commit; expect fail "8 stale id listed"
setup; sed -i 's/^    x();/    x();\n    \/\/ >>> [HOOK:T2]\n    if (H.b()) {\n      return;\n    }\n    \/\/ <<< [HOOK:T2]/' core/java/A.java; listed T2; commit; expect ok "9 marked block"
setup; sed -i 's/^    x();/    x();\n    \/\/ >>> [HOOK:T2]\n    if (H.b()) return;/' core/java/A.java; listed T2; commit; expect fail "10 unclosed block"
setup; printf '<property name="java.release" value="21"/>\n' > build.xml; commit; expect fail "11 root build.xml Java release differs from core"
setup; printf '<property name="java.release" value="27"/>\n' > build.xml; commit; expect ok "12 root build.xml in sync"
setup; printf 'class A {\r\n  void a() {\r\n  }\r\n}\r\n' > core/java/A.java; git add -A; git commit -qm "vendor: crlf"; sed -i 's/^  void a() {\r$/  void a() {\r\n    H.a(); \/\/ [HOOK:T1]\r/' core/java/A.java; listed T1; commit; expect ok "13 CRLF file, marked hook"

echo "check-hooks self-test: $pass passed, $failed failed"
[ "$failed" = 0 ]
