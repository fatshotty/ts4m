#!/usr/bin/env bash
# Builds the per-ABI release APKs and publishes a GitHub Release that the in-app
# updater consumes. versionCode = git commit count (matches build.gradle.kts).
#
# Requires: gh CLI authenticated (gh auth login), the tgengine AAR present
# (run tgengine/build-aar.sh first if you changed the Go engine).
# Release signing must be configured through environment variables or Gradle
# properties:
#   TGSTREAM_RELEASE_STORE_FILE
#   TGSTREAM_RELEASE_STORE_PASSWORD
#   TGSTREAM_RELEASE_KEY_ALIAS
#   TGSTREAM_RELEASE_KEY_PASSWORD
#
# Usage:  ./release.sh ["release notes"]
set -euo pipefail
cd "$(dirname "$0")"

# Force the Android Studio JBR (Java 21): AGP needs >=17, the shell may default
# to an older system JDK (Coursier Java 11).
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"

REPO="fatshotty/ts4m"
NOTES="${1:-}"

VC="$(git rev-list --count HEAD)"
VN="${TGSTREAM_VERSION_NAME:-1.0.0}"
TAG="v$VN"

if [ -n "$(git status --porcelain)" ]; then
  echo "Refusing to release from a dirty working tree."
  echo "Commit or stash changes first so versionCode=$VC maps to an exact source state."
  exit 1
fi

if [ ! -f app/libs/tgengine.aar ]; then
  echo "tgengine.aar missing — building it…"
  bash tgengine/build-aar.sh
fi

for abi in arm64-v8a armeabi-v7a; do
  if ! unzip -l app/libs/tgengine.aar "jni/$abi/libgojni.so" >/dev/null; then
    echo "tgengine.aar does not contain jni/$abi/libgojni.so."
    echo "Run bash tgengine/build-aar.sh before publishing."
    exit 1
  fi
done

echo "Building $TAG (versionCode=$VC)…"
./gradlew :app:assembleRelease -PtgstreamVersionCode="$VC" -PtgstreamVersionName="$VN"

OUT="app/build/outputs/apk/release"
WORK="$(mktemp -d)"

copy_release_apk() {
  local abi="$1"
  local src="$OUT/app-$abi-release.apk"
  local unsigned="$OUT/app-$abi-release-unsigned.apk"
  if [ -f "$src" ]; then
    cp "$src" "$WORK/tgstream-$abi.apk"
    return
  fi
  if [ -f "$unsigned" ]; then
    echo "Refusing to publish unsigned release APK: $unsigned"
    echo "Configure TGSTREAM_RELEASE_STORE_FILE, TGSTREAM_RELEASE_STORE_PASSWORD,"
    echo "TGSTREAM_RELEASE_KEY_ALIAS, and TGSTREAM_RELEASE_KEY_PASSWORD."
    exit 1
  fi
  echo "Missing release APK for $abi in $OUT."
  exit 1
}

copy_release_apk arm64-v8a
copy_release_apk armeabi-v7a
python3 -c 'import json,sys; json.dump({"versionCode": int(sys.argv[1]), "versionName": sys.argv[2], "notes": sys.argv[3]}, sys.stdout); print()' "$VC" "$VN" "$NOTES" > "$WORK/version.json"

echo "Publishing release ${TAG} to ${REPO} ..."
gh release create "$TAG" \
  "$WORK/tgstream-arm64-v8a.apk" \
  "$WORK/tgstream-armeabi-v7a.apk" \
  "$WORK/version.json" \
  --repo "$REPO" \
  --title "$TAG" \
  --notes "${NOTES:-tgstream $TAG}"

echo "Done → https://github.com/$REPO/releases/tag/$TAG"
echo "Friends update in-app; or share: https://github.com/$REPO/releases/latest"
