#!/usr/bin/env bash
# Signiert und notarisiert das macOS-Binary, damit es nach einem Download ohne `xattr` startet (design §9).
#
# Aufruf: ./scripts/sign-macos.sh [--no-build]
#
# Voraussetzungen (einmalig, siehe README „Entwicklung“):
#   - Zertifikat „Developer ID Application“ im Schlüsselbund
#   - Notary-Zugang: xcrun notarytool store-credentials kmob-notary --apple-id … --team-id …
#
# Umgebung (optional):
#   KMOB_SIGN_IDENTITY    Signier-Identität (Default: erste „Developer ID Application“ im Schlüsselbund)
#   KMOB_NOTARY_PROFILE   Profilname für notarytool (Default: kmob-notary)
#
# Ergebnis in build/dist/: kmob (signiert, notarisiert), kmob-<version>-macosArm64.tar.gz und .sha256
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST="$ROOT/build/dist"
PROFILE="${KMOB_NOTARY_PROFILE:-kmob-notary}"
BUNDLE_ID="de.kmob.kmob"
VERSION="$(sed -n 's/^version=//p' "$ROOT/gradle.properties")"
SOURCE="$ROOT/build/bin/macosArm64/releaseExecutable/kmob.kexe"

fail() { echo "sign-macos: $*" >&2; exit 1; }
step() { echo "▶ $*"; }

[[ "$(uname -s)-$(uname -m)" == "Darwin-arm64" ]] || fail "needs macOS on Apple Silicon"

IDENTITY="${KMOB_SIGN_IDENTITY:-$(security find-identity -v -p codesigning \
  | sed -n 's/.*"\(Developer ID Application: .*\)"/\1/p' | head -1)}"
[[ -n "$IDENTITY" ]] || fail "no 'Developer ID Application' identity in the keychain (or set KMOB_SIGN_IDENTITY)"

if [[ "${1:-}" != "--no-build" ]]; then
  step "build release binary"
  (cd "$ROOT" && ./gradlew -q linkReleaseExecutableMacosArm64)
fi
[[ -f "$SOURCE" ]] || fail "binary not found: $SOURCE"

rm -rf "$DIST"; mkdir -p "$DIST"
cp "$SOURCE" "$DIST/kmob"

step "sign with $IDENTITY"
codesign --force --sign "$IDENTITY" --options runtime --timestamp --identifier "$BUNDLE_ID" "$DIST/kmob"
codesign --verify --strict --verbose=2 "$DIST/kmob"

step "notarize (profile $PROFILE, waits for Apple)"
ZIP="$DIST/kmob-notarize.zip"
ditto -c -k --keepParent "$DIST/kmob" "$ZIP"
RESULT="$(xcrun notarytool submit "$ZIP" --keychain-profile "$PROFILE" --wait --output-format json)"
rm -f "$ZIP"
SUBMISSION_ID="$(sed -n 's/.*"id" *: *"\([^"]*\)".*/\1/p' <<<"$RESULT")"
STATUS="$(sed -n 's/.*"status" *: *"\([^"]*\)".*/\1/p' <<<"$RESULT")"
if [[ "$STATUS" != "Accepted" ]]; then
  echo "$RESULT" >&2
  [[ -n "$SUBMISSION_ID" ]] && xcrun notarytool log "$SUBMISSION_ID" --keychain-profile "$PROFILE" >&2 || true
  fail "notarization not accepted (status: ${STATUS:-unknown})"
fi
echo "  accepted: $SUBMISSION_ID"

step "verify: runs like a browser download (quarantine attribute set)"
PROBE="$DIST/probe"; mkdir -p "$PROBE"; cp "$DIST/kmob" "$PROBE/kmob"
xattr -w com.apple.quarantine "0081;$(printf '%x' "$(date +%s)");Safari;$(uuidgen)" "$PROBE/kmob"
OUT="$(KMOB_MOB_BIN=/usr/bin/true "$PROBE/kmob" version 2>&1)" || fail "quarantined binary did not start: $OUT"
grep -q "kmob $VERSION" <<<"$OUT" || fail "unexpected output: $OUT"
rm -rf "$PROBE"
echo "  ok: $(head -1 <<<"$OUT")"

step "package"
ARCHIVE="kmob-$VERSION-macosArm64.tar.gz"
tar -czf "$DIST/$ARCHIVE" -C "$DIST" kmob
(cd "$DIST" && shasum -a 256 "$ARCHIVE" > "$ARCHIVE.sha256")
echo
echo "done: $DIST/$ARCHIVE"
cat "$DIST/$ARCHIVE.sha256"
