package tgengine

import (
	"encoding/base64"
	"testing"
)

// obfuscate mirrors build-aar.sh: XOR with obfKey, then base64. Used only by the
// test to produce inputs for deobfuscate.
func obfuscate(plain string) string {
	raw := []byte(plain)
	enc := make([]byte, len(raw))
	for i := range raw {
		enc[i] = raw[i] ^ obfKey[i%len(obfKey)]
	}
	return base64.StdEncoding.EncodeToString(enc)
}

func TestDeobfuscateRoundTrip(t *testing.T) {
	for _, plain := range []string{"12345", "0123456789abcdef0123456789abcdef", "a", ""} {
		if got := deobfuscate(obfuscate(plain)); got != plain {
			t.Fatalf("deobfuscate(obfuscate(%q)) = %q, want %q", plain, got, plain)
		}
	}
}

func TestDeobfuscateMalformed(t *testing.T) {
	if got := deobfuscate("not valid base64!!"); got != "" {
		t.Fatalf("deobfuscate(malformed) = %q, want empty", got)
	}
}

func TestDefaultsAbsentWhenNotInjected(t *testing.T) {
	// In tests no -ldflags injection happens, so the defaults must read as absent.
	if HasDefaultCredentials() {
		t.Fatalf("HasDefaultCredentials() = true without build-time injection")
	}
	if DefaultApiID() != 0 || DefaultApiHash() != "" {
		t.Fatalf("defaults non-empty without injection: id=%d hash=%q", DefaultApiID(), DefaultApiHash())
	}
}
