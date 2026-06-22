#!/usr/bin/env bash
# Builds the per-ABI debug APKs and publishes a GitHub Release that the in-app
# updater consumes. versionCode = git commit count (matches build.gradle.kts).
#
# Requires: gh CLI authenticated (gh auth login), the tgengine AAR present
# (run tgengine/build-aar.sh first if you changed the Go engine).
#
# Usage:  ./release.sh ["release notes"]
set -euo pipefail
cd "$(dirname "$0")"

export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"

REPO="fatshotty/ts4m"
NOTES="${1:-}"

VC="$(git rev-list --count HEAD)"
VN="0.1.$VC"
TAG="v$VN"

if [ ! -f app/libs/tgengine.aar ]; then
  echo "tgengine.aar missing — building it…"
  bash tgengine/build-aar.sh
fi

echo "Building $TAG (versionCode=$VC)…"
./gradlew :app:assembleDebug

OUT="app/build/outputs/apk/debug"
WORK="$(mktemp -d)"
cp "$OUT/app-arm64-v8a-debug.apk"   "$WORK/tgstream-arm64-v8a.apk"
cp "$OUT/app-armeabi-v7a-debug.apk" "$WORK/tgstream-armeabi-v7a.apk"
printf '{ "versionCode": %s, "versionName": "%s", "notes": "%s" }\n' "$VC" "$VN" "$NOTES" > "$WORK/version.json"

echo "Publishing release $TAG to $REPO…"
gh release create "$TAG" \
  "$WORK/tgstream-arm64-v8a.apk" \
  "$WORK/tgstream-armeabi-v7a.apk" \
  "$WORK/version.json" \
  --repo "$REPO" \
  --title "$TAG" \
  --notes "${NOTES:-tgstream $TAG}"

echo "Done → https://github.com/$REPO/releases/tag/$TAG"
echo "Friends update in-app; or share: https://github.com/$REPO/releases/latest"
