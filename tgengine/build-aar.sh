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

# Built-in Telegram API credentials (optional). Plaintext lives ONLY in the
# git-ignored credentials.local (or in the env), never in git or the sources.
# We XOR+base64 them here and inject the result into the Go binary via -ldflags,
# so `strings` on the .so shows only gibberish. The XOR key MUST match obfKey in
# credentials.go. With no credentials provided the app falls back to asking the
# user for their own (unchanged behavior).
OBF_KEY='Tg5tr3am!2026#xK'
[ -f credentials.local ] && . ./credentials.local
API_ID="${API_ID:-}"
API_HASH="${API_HASH:-}"

obf() { # XOR $1 with OBF_KEY, base64-encode
  OBF_KEY="$OBF_KEY" python3 - "$1" <<'PY'
import os, sys, base64
key = os.environ["OBF_KEY"].encode()
s = sys.argv[1].encode()
print(base64.standard_b64encode(bytes(b ^ key[i % len(key)] for i, b in enumerate(s))).decode())
PY
}

LDFLAGS=""
if [ -n "$API_ID" ] && [ -n "$API_HASH" ]; then
  echo "Injecting built-in API credentials (obfuscated)…"
  LDFLAGS="-X tgstream/tgengine.defaultApiIDEnc=$(obf "$API_ID") -X tgstream/tgengine.defaultApiHashEnc=$(obf "$API_HASH")"
else
  echo "No built-in credentials (set API_ID/API_HASH in credentials.local to bake them in)."
fi

echo "Building tgengine.aar (NDK: $ANDROID_NDK_HOME)…"
go build ./...
gomobile bind -target=android -androidapi 21 ${LDFLAGS:+-ldflags="$LDFLAGS"} -o ../app/libs/tgengine.aar .
echo "Done → app/libs/tgengine.aar"
