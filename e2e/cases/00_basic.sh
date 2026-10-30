# shellcheck shell=bash
# M0: Binary startet

test_case "kmob startet"
out="$(kmob version 2>&1 || true)"
check "gibt die kmob-Version aus" contains "$out" "kmob "
