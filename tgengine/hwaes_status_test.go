package tgengine

import "testing"

func TestDecryptHardwareStatusForRuntime(t *testing.T) {
	cases := []struct {
		name  string
		arch  string
		hwAES bool
		want  string
	}{
		{"arm64 uses standard library hardware AES", "arm64", false, DecryptHardwareNative},
		{"arm with AES uses upstream accelerated decrypt path", "arm", true, "accelerated-arm32"},
		{"arm without AES falls back to software", "arm", false, DecryptHardwareSoftware},
		{"unknown arch is reported as unknown", "mips", false, DecryptHardwareUnknown},
	}

	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if got := decryptHardwareStatusForRuntime(c.arch, c.hwAES); got != c.want {
				t.Fatalf("decryptHardwareStatusForRuntime(%q, %v) = %q, want %q",
					c.arch, c.hwAES, got, c.want)
			}
		})
	}
}
