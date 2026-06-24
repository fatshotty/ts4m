module tgstream/tgengine

go 1.25.0

tool golang.org/x/mobile/cmd/gobind

require (
	github.com/gotd/td v0.111.0
	go.uber.org/zap v1.27.0
)

// Local patched copy: ARMv8 hardware AES for the MTProto IGE decrypt path on
// 32-bit ARM boxes (see crypto/hwaes_*). ~50x faster than Go's software AES.
replace github.com/gotd/td => ./third_party/gotd-td

require (
	github.com/cenkalti/backoff/v4 v4.3.0 // indirect
	github.com/go-faster/errors v0.7.1 // indirect
	github.com/go-faster/jx v1.1.0 // indirect
	github.com/go-faster/xor v1.0.0 // indirect
	github.com/gotd/ige v0.2.2 // indirect
	github.com/gotd/neo v0.1.5 // indirect
	github.com/klauspost/compress v1.17.10 // indirect
	github.com/segmentio/asm v1.2.0 // indirect
	go.opentelemetry.io/otel v1.29.0 // indirect
	go.opentelemetry.io/otel/trace v1.29.0 // indirect
	go.uber.org/atomic v1.11.0 // indirect
	go.uber.org/multierr v1.11.0 // indirect
	golang.org/x/crypto v0.53.0 // indirect
	golang.org/x/mobile v0.0.0-20260611195102-4dd8f1dbf5d2 // indirect
	golang.org/x/mod v0.37.0 // indirect
	golang.org/x/net v0.56.0 // indirect
	golang.org/x/sync v0.21.0 // indirect
	golang.org/x/sys v0.46.0 // indirect
	golang.org/x/tools v0.46.0 // indirect
	nhooyr.io/websocket v1.8.11 // indirect
	rsc.io/qr v0.2.0 // indirect
)
