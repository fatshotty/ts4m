//go:build !arm

package crypto

// hwIGEDecrypt is a no-op on every arch except 32-bit ARM. arm64/amd64 already
// get hardware AES from the standard library, so the software IGE path there is
// already accelerated and no cgo is needed.
func hwIGEDecrypt(key, iv, dst, src []byte) bool { return false }
