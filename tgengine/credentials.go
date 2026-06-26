package tgengine

import (
	"encoding/base64"
	"strconv"
)

// Built-in (the developer's own) Telegram API credentials.
//
// These two strings are EMPTY in source and in git. The real values are injected
// at build time by build-aar.sh via the linker:
//
//	-ldflags "-X tgstream/tgengine.defaultApiIDEnc=<b64> -X tgstream/tgengine.defaultApiHashEnc=<b64>"
//
// They are stored XOR-obfuscated + base64, so `strings` on the compiled .so shows
// only gibberish, not the plaintext. This is OBFUSCATION, not security: the XOR
// key lives in this same binary, so a determined reverse-engineer can still
// recover them — it only stops casual extraction. Credentials a user types into
// Settings take priority over these and are handled entirely on the Kotlin side.
var (
	defaultApiIDEnc   string
	defaultApiHashEnc string
)

// obfKey is the repeating XOR key used to obfuscate the baked-in defaults. It
// MUST match the key in build-aar.sh that produced defaultApiIDEnc/HashEnc.
var obfKey = []byte("Tg5tr3am!2026#xK")

// deobfuscate reverses the build-time XOR+base64 obfuscation. Returns "" for an
// empty or malformed input (i.e. when no credentials were baked in).
func deobfuscate(enc string) string {
	if enc == "" {
		return ""
	}
	raw, err := base64.StdEncoding.DecodeString(enc)
	if err != nil {
		return ""
	}
	out := make([]byte, len(raw))
	for i := range raw {
		out[i] = raw[i] ^ obfKey[i%len(obfKey)]
	}
	return string(out)
}

// DefaultApiID returns the baked-in default API id, or 0 if the build had none.
func DefaultApiID() int {
	n, _ := strconv.Atoi(deobfuscate(defaultApiIDEnc))
	return n
}

// DefaultApiHash returns the baked-in default API hash, or "" if the build had none.
func DefaultApiHash() string { return deobfuscate(defaultApiHashEnc) }

// HasDefaultCredentials reports whether the app was built with usable defaults.
func HasDefaultCredentials() bool {
	return DefaultApiID() > 0 && DefaultApiHash() != ""
}
