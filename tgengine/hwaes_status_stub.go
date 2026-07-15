//go:build !arm || !cgo || (!linux && !android)
// +build !arm !cgo !linux,!android

package tgengine

func arm32HardwareAESAvailable() bool {
	return false
}
