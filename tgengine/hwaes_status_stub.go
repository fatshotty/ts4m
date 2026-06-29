//go:build !arm

package tgengine

func arm32HardwareAESAvailable() bool {
	return false
}
