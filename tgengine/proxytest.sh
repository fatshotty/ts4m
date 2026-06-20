#!/usr/bin/env bash
# End-to-end desktop test for the tgengine proxy (range + parallel getFile).
#
# Required env:
#   TG_API_ID, TG_API_HASH   your Telegram credentials
#   TG_SESSION               path to an existing gotd session (default: reuse spike-gotd)
#   TG_CHANNEL               channel id (as in your .strm, e.g. -100... or raw)
#   TG_MSGS                  message id(s), dash-separated (e.g. 1234 or 1234-1235)
#   TG_CONNS                 parallel connections (default 3)
#
# Usage:
#   TG_API_ID=.. TG_API_HASH=.. TG_CHANNEL=.. TG_MSGS=.. bash proxytest.sh
set -u

export PATH="/opt/homebrew/bin:$PATH"
: "${TG_SESSION:=../spike-gotd/spike-session.json}"
: "${TG_CONNS:=3}"
: "${TG_CHANNEL:?set TG_CHANNEL}"
: "${TG_MSGS:?set TG_MSGS}"

cd "$(dirname "$0")"

LOG=$(mktemp)
echo "Starting proxy (session=$TG_SESSION)..."
TG_SESSION="$TG_SESSION" go run ./cmd/proxytest >"$LOG" 2>&1 &
PID=$!
trap 'kill $PID 2>/dev/null' EXIT

# Wait for the proxy to come up and print its port.
PORT=""
for i in $(seq 1 120); do
  PORT=$(grep -oE 'PROXY READY on http://127.0.0.1:[0-9]+' "$LOG" | grep -oE '[0-9]+$' | head -1)
  [ -n "$PORT" ] && break
  if ! kill -0 $PID 2>/dev/null; then echo "proxy exited early:"; cat "$LOG"; exit 1; fi
  sleep 0.5
done
[ -z "$PORT" ] && { echo "proxy did not start:"; cat "$LOG"; exit 1; }
echo "Proxy on port $PORT"

BASE="http://127.0.0.1:$PORT/stream?channel=$TG_CHANNEL&messages=$TG_MSGS&conns=$TG_CONNS"

check_range () {
  local label="$1" range="$2" expect="$3"
  local hdr; hdr=$(curl -s -D - -o /tmp/tg_$label.bin -H "Range: bytes=$range" "$BASE")
  local status; status=$(printf '%s' "$hdr" | head -1 | tr -d '\r')
  local clen; clen=$(printf '%s' "$hdr" | grep -i '^Content-Range:' | tr -d '\r')
  local bytes; bytes=$(wc -c </tmp/tg_$label.bin 2>/dev/null | tr -d ' '); bytes=${bytes:-0}
  echo "[$label] $status | $clen | got=$bytes expect=$expect"
  [ "$bytes" = "$expect" ] || { echo "  ❌ size mismatch"; return 1; }
  echo "  ✅"
}

echo "== first 1 MiB =="
check_range first "0-1048575" 1048576 || FAIL=1
echo "== seek: 1 MiB at 2 MiB offset =="
check_range seek "2097152-3145727" 1048576 || FAIL=1

if [ "${FAIL:-0}" = "1" ]; then
  echo "RESULT: FAIL ❌"; echo "--- proxy log ---"; cat "$LOG"; exit 1
fi
echo "RESULT: PROXY OK ✅  (range + ${TG_CONNS}-way parallel getFile working)"
