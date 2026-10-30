# shellcheck shell=bash
# M1: transparenter Wrapper (tasks 1.5)

# Fake-mob: protokolliert seine Argumente (eins pro Zeile) und endet mit $FAKE_MOB_EXIT.
FAKE_MOB="$WORK/bin/fake-mob"
cat > "$FAKE_MOB" <<'EOF'
#!/bin/sh
: > "$FAKE_MOB_LOG"
for a in "$@"; do printf '%s\n' "$a" >> "$FAKE_MOB_LOG"; done
[ -n "$FAKE_MOB_SLEEP" ] && exec sleep "$FAKE_MOB_SLEEP"
exit "${FAKE_MOB_EXIT:-0}"
EOF
chmod +x "$FAKE_MOB"
export FAKE_MOB_LOG="$WORK/fake-mob.log"

test_case "Passthrough mit identischen Argumenten"
set +e
KMOB_MOB_BIN="$FAKE_MOB" FAKE_MOB_EXIT=4 kmob status --yes -- "a b" ""; rc=$?
set -e
check "Exit-Code von mob" [ "$rc" -eq 4 ]
check "Argumente identisch" [ "$(cat "$FAKE_MOB_LOG")" = "$(printf 'status\n--yes\n--\na b\n')" ]

test_case "Aliase und erweiterte Befehle reichen an mob durch"
KMOB_MOB_BIN="$FAKE_MOB" kmob n --stay >/dev/null 2>&1 || true
check "n --stay" [ "$(cat "$FAKE_MOB_LOG")" = "$(printf 'n\n--stay\n')" ]

test_case "mob fehlt"
set +e
out="$(KMOB_MOB_BIN="$WORK/nope" kmob status 2>&1)"; rc=$?
set -e
check "Exit 1" [ "$rc" -eq 1 ]
check "Meldung nennt KMOB_MOB_BIN" contains "$out" "KMOB_MOB_BIN=$WORK/nope is not an executable file"

test_case "Ctrl+C beendet mob (K5-02)"
set +e
set -m                                   # eigene Prozessgruppe, wie im Terminal
KMOB_MOB_BIN="$FAKE_MOB" FAKE_MOB_SLEEP=30 kmob timer 10 & pid=$!
set +m
sleep 1
kill -INT -- "-$pid"
wait "$pid"; rc=$?
set -e
check "Exit 130 (mob per SIGINT beendet)" [ "$rc" -eq 130 ]

test_case "echtes mob: version und status"
out="$(kmob version 2>&1)"
check "kmob-Version" contains "$out" "kmob "
check "mob-Version" contains "$out" "v5."
setup_repos passthrough
cd "$REPO/a"
expected="$(mob status 2>&1; echo "rc=$?")"
actual="$(kmob status 2>&1; echo "rc=$?")"
check "kmob status = mob status" [ "$expected" = "$actual" ]

test_case "echtes mob: start / next / done ohne Session"
kmob start >/dev/null 2>&1
check "auf WIP-Branch" [ "$(git branch --show-current)" = "mob/main" ]
echo change > code.txt
kmob next >/dev/null 2>&1
check "WIP-Branch remote" quiet git --git-dir="$REPO/origin.git" rev-parse -q --verify mob/main
kmob start >/dev/null 2>&1
kmob done >/dev/null 2>&1
check "Änderung im Index von main" [ "$(git diff --cached --name-only)" = "code.txt" ]
cd "$WORK"
