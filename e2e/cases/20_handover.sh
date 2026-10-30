# shellcheck shell=bash
# M2: Session-Übergabe im Happy Path (tasks 2.11, design §8 E2E 1, 2, 4, 5 + Secret)

export KMOB_KIRO_SESSIONS_DIR="$WORK/sessions"
mkdir -p "$KMOB_KIRO_SESSIONS_DIR"

# fake_session <id> <cwd> <title> [<zusätzliche log-zeile>]
fake_session() {
  local id="$1" cwd="$2" title="$3" extra="${4:-}"
  printf '{"session_id":"%s","cwd":"%s","created_at":"2026-10-29T10:00:00Z","updated_at":"2026-10-29T10:00:00Z","title":"%s","session_state":{"todo":[1]}}\n' \
    "$id" "$cwd" "$title" > "$KMOB_KIRO_SESSIONS_DIR/$id.json"
  {
    printf '{"version":"v1","kind":"Prompt","data":{"content":"hello from %s"}}\n' "$title"
    printf '{"version":"v1","kind":"AssistantMessage","data":{"content":"ok"}}\n'
    [ -n "$extra" ] && printf '%s\n' "$extra"
    printf '{"version":"v1","kind":"AssistantMessage","data":{"content":"still runn'   # unvollständige letzte Zeile
  } > "$KMOB_KIRO_SESSIONS_DIR/$id.jsonl"
}

# Prüft Inhalt eines Artefakts gegen die Quelldateien (Format aus Spike S1).
artifact_matches() { # <artifact> <session-id>
  python3 - "$1" "$KMOB_KIRO_SESSIONS_DIR/$2" <<'EOF'
import json, sys
art = json.load(open(sys.argv[1]))
base = sys.argv[2]
meta = json.load(open(base + ".json"))
lines = [json.loads(l) for l in open(base + ".jsonl").read().split("\n")[:-1] if l.strip()]
ok = art["format"] == "kiro-session-export-v1" and art["metadata"] == meta and art["log_entries"] == lines
sys.exit(0 if ok else 1)
EOF
}

remote_files() { git --git-dir="$REPO/origin.git" ls-tree -r --name-only "$1" 2>/dev/null; }

setup_repos handover
fake_session anna-1 "$REPO/a" "Refactoring Payment-Service"
fake_session other-repo "$WORK/elsewhere" "Fremdes Repo"

test_case "A: kmob next ohne Bestätigung (kein TTY)"
cd "$REPO/a"
kmob start >/dev/null 2>&1
mkdir -p sub && echo one > sub/code.txt
cd sub
set +e; out="$(kmob next </dev/null 2>&1)"; rc=$?; set -e
check "Exit 1" [ "$rc" -eq 1 ]
check "Meldung" contains "$out" "needs confirmation"
check "nichts geschrieben" [ ! -e "$REPO/a/.kmob" ]
check "nichts gepusht" bash -c '! grep -q kmob <<<"$1"' _ "$(remote_files mob/main)"

test_case "A: kmob next --yes aus Unterverzeichnis"
out="$(kmob next --yes 2>&1)"
check "Auswahl-Meldung" contains "$out" "handing over Kiro session anna-1 \"Refactoring Payment-Service\""
check ".kmob/ im Git-Root, nicht im Unterverzeichnis" [ -f "$REPO/a/.kmob/kiro-session.json" ] && [ ! -e "$REPO/a/sub/.kmob" ]
check "Artefakt im WIP-Branch" contains "$(remote_files mob/main)" ".kmob/kiro-session.json"
check "Handover-Info im WIP-Branch" contains "$(remote_files mob/main)" ".kmob/handover.json"
check "Inhalt = Export der Session (ohne unvollständige Zeile)" artifact_matches "$REPO/a/.kmob/kiro-session.json" anna-1
check "kein tmp im git-dir" [ ! -e "$REPO/a/.git/kmob/tmp" ]

test_case "B: kmob start zeigt Lade-Befehl"
cd "$REPO/b"
out="$(kmob start 2>&1)"
check "Absender und Titel" contains "$out" "Kiro session from Anna, handed over"
check "Titel" contains "$out" "\"Refactoring Payment-Service\" (2 entries"
check "absoluter Pfad" contains "$out" "/chat load $REPO/b/.kmob/kiro-session.json"

test_case "B: kmob next mit eigener Session"
fake_session bob-1 "$REPO/b" "Weiter mit Tests"
echo two >> sub/code.txt
out="$(kmob next --yes 2>&1)"
check "Bob-Session gewählt" contains "$out" "handing over Kiro session bob-1"
check "Artefakt aktualisiert" artifact_matches "$REPO/b/.kmob/kiro-session.json" bob-1

test_case "A: kmob start + kmob done"
cd "$REPO/a"
out="$(kmob start 2>&1)"
check "Session von Bob" contains "$out" "from Bob"
kmob done >/dev/null 2>&1
check "auf main" [ "$(git branch --show-current)" = "main" ]
check "nichts aus .kmob/ im Index" [ -z "$(git ls-files .kmob)" ]
check ".kmob/ nicht im Working Tree" [ ! -e .kmob ]
check "Code im Index" contains "$(git diff --cached --name-only)" "sub/code.txt"
check "WIP-Branch remote gelöscht" [ -z "$(remote_files mob/main)" ]
git commit -qm "feature"
git push -q origin main

test_case "kmob next auf dem Basis-Branch"
out="$(kmob next --yes 2>&1 || true)"
check "kein .kmob/ (K5-03)" [ ! -e .kmob ]
check "keine Auswahl-Meldung" bash -c '! grep -q "handing over" <<<"$1"' _ "$out"

test_case "Secret in der Session bricht ab"
kmob start >/dev/null 2>&1
key="AK""IA$(python3 -c 'import random; print("".join(random.choice("ABCDEFGHIJKLMNOPQRSTUVWXYZ234567") for _ in range(16)))')"
fake_session anna-2 "$REPO/a" "Mit Secret" "{\"version\":\"v1\",\"kind\":\"ToolResults\",\"data\":{\"stdout\":\"AWS_ACCESS_KEY_ID=$key\"}}"
touch -t 203001010000 "$KMOB_KIRO_SESSIONS_DIR/anna-2.jsonl"   # sicher die neueste
echo three > s.txt
set +e; out="$(kmob next --yes 2>&1)"; rc=$?; set -e
check "Exit 1" [ "$rc" -eq 1 ]
check "Regel und Log-Eintrag genannt" contains "$out" "possible secret (kmob-aws-access-key-id) in log entry #3"
check "Wert nicht ausgegeben" bash -c '! grep -q "$2" <<<"$1"' _ "$out" "$key"
check "kein Artefakt" [ ! -e .kmob ]
check "nicht gepusht" bash -c '! grep -q kmob <<<"$1"' _ "$(remote_files mob/main)"
cd "$WORK"
rm -rf "$KMOB_KIRO_SESSIONS_DIR"
unset KMOB_KIRO_SESSIONS_DIR
