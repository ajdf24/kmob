#!/usr/bin/env bash
# End-to-End-Tests für kmob gegen ein lokales Bare-Repo und das echte mob (design §8).
# Aufruf: ./e2e/run.sh [--no-build]
# Kompatibel mit bash 3.2 (macOS-Standard).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

case "$(uname -s)-$(uname -m)" in
  Darwin-arm64) TARGET=macosArm64; TASK=MacosArm64 ;;
  Linux-x86_64) TARGET=linuxX64; TASK=LinuxX64 ;;
  *) echo "unsupported host: $(uname -sm)" >&2; exit 1 ;;
esac

if [[ "${1:-}" != "--no-build" ]]; then
  (cd "$ROOT" && ./gradlew -q "linkReleaseExecutable$TASK" 2>&1 | grep -v 'is deprecated' || true)
fi
KMOB_BIN="$ROOT/build/bin/$TARGET/releaseExecutable/kmob.kexe"
[[ -x "$KMOB_BIN" ]] || { echo "kmob binary not found: $KMOB_BIN" >&2; exit 1; }
command -v mob >/dev/null || { echo "mob not found in PATH – install from https://mob.sh" >&2; exit 1; }

# ---------------------------------------------------------------- Umgebung
WORK="$(cd "$(mktemp -d)" && pwd -P)"   # aufgelöst (/tmp → /private/tmp)
trap 'rm -rf "$WORK"' EXIT
trap 'echo "  ✗ aborted in \"$CURRENT\" (${BASH_SOURCE[0]##*/}:$LINENO: $BASH_COMMAND)" >&2' ERR
mkdir -p "$WORK/bin"
ln -s "$KMOB_BIN" "$WORK/bin/kmob"
export PATH="$WORK/bin:$PATH"
export MOB_TIMER_LOCAL=false MOB_TIMER_ROOM="" MOB_REQUIRE_COMMIT_MESSAGE=false MOB_SKIP_CI_PUSH_OPTION_ENABLED=false
export HOME="$WORK/home"; mkdir -p "$HOME"   # kein ~/.mob, kein echtes ~/.kiro
export GIT_CONFIG_NOSYSTEM=1
git config --global init.defaultBranch main
git config --global user.name tester
git config --global user.email tester@example.com
unset KIRO_SESSION_ID

PASS=0; FAIL=0; CURRENT=""
test_case() { CURRENT="$1"; echo "▶ $1"; }
ok()   { PASS=$((PASS + 1)); echo "  ✓ $1"; }
fail() { FAIL=$((FAIL + 1)); echo "  ✗ $1" >&2; }
check() { # check "<beschreibung>" <befehl…>
  local d="$1"; shift
  if "$@"; then ok "$d"; else fail "$d"; fi
}
contains() { [[ "$1" == *"$2"* ]]; }
quiet() { "$@" >/dev/null 2>&1; }

# Frisches Bare-Repo mit den Clones A und B, Basis-Branch main mit einem Commit.
setup_repos() {
  local r="$WORK/repo-$1"; rm -rf "$r"; mkdir -p "$r"
  git init -q --bare "$r/origin.git"
  git clone -q "$r/origin.git" "$r/a" 2>/dev/null
  (cd "$r/a" && git config user.name Anna && echo hello > README && git add . && git commit -qm init && git push -q origin main)
  git clone -q "$r/origin.git" "$r/b" 2>/dev/null
  (cd "$r/b" && git config user.name Bob)
  REPO="$r"
}

# shellcheck source=/dev/null
for f in "$ROOT"/e2e/cases/*.sh; do source "$f"; done

echo
echo "E2E: $PASS passed, $FAIL failed"
[[ $FAIL -eq 0 ]]
