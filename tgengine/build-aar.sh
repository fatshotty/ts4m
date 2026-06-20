#!/usr/bin/env bash
# Regenerates app/libs/tgengine.aar from the Go sources in this directory using
# gomobile. The AAR is a build artifact (git-ignored); run this after changing
# any tgengine/*.go file, then rebuild the Android app.
#
# Requires: Go, gomobile+gobind (~/go/bin), Android SDK + NDK.
set -euo pipefail

export PATH="/opt/homebrew/bin:$HOME/go/bin:$PATH"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort | tail -1)}"

cd "$(dirname "$0")"
echo "Building tgengine.aar (NDK: $ANDROID_NDK_HOME)…"
go build ./...
gomobile bind -target=android -androidapi 21 -o ../app/libs/tgengine.aar .
echo "Done → app/libs/tgengine.aar"
