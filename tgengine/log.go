package tgengine

import (
	"fmt"
	"strings"
	"sync"

	"go.uber.org/zap"
	"go.uber.org/zap/zapcore"
)

// Logger receives engine log lines. Implemented in Kotlin and forwarded to the
// on-device log so the Go engine's behavior is visible without adb.
//
// level is one of "I", "W", "E", or "GO" (gotd's internal logger).
type Logger interface {
	Log(level string, msg string)
}

var (
	logMu  sync.RWMutex
	logSink Logger
)

// SetLogger registers (or clears, with nil) the log sink. Package-level so the
// proxy and streaming goroutines can log without an Engine reference.
func SetLogger(l Logger) {
	logMu.Lock()
	logSink = l
	logMu.Unlock()
}

func emit(level, msg string) {
	logMu.RLock()
	l := logSink
	logMu.RUnlock()
	if l != nil {
		l.Log(level, msg)
	}
}

func logI(format string, args ...interface{}) { emit("I", fmt.Sprintf(format, args...)) }
func logW(format string, args ...interface{}) { emit("W", fmt.Sprintf(format, args...)) }
func logE(format string, args ...interface{}) { emit("E", fmt.Sprintf(format, args...)) }

// goSink adapts zap output (gotd's internal logger) into our log sink.
type goSink struct{}

func (goSink) Write(p []byte) (int, error) {
	emit("GO", strings.TrimRight(string(p), "\n"))
	return len(p), nil
}
func (goSink) Sync() error { return nil }

// zapLogger builds the *zap.Logger handed to gotd so its connection / flood-wait
// / RPC events flow into our on-device log.
func zapLogger() *zap.Logger {
	cfg := zap.NewProductionEncoderConfig()
	cfg.TimeKey = "" // AppLog already timestamps each line
	enc := zapcore.NewConsoleEncoder(cfg)
	// WARN keeps gotd's noisy per-connection INFO chatter (Key already exists,
	// SessionInit, Initialized) out of the on-device ring buffer so the useful
	// lines (our own logs + real warnings/errors) aren't scrolled away.
	core := zapcore.NewCore(enc, zapcore.AddSync(goSink{}), zapcore.WarnLevel)
	return zap.New(core)
}
