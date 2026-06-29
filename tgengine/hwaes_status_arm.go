//go:build arm

package tgengine

/*
#include <sys/auxv.h>

#ifndef AT_HWCAP2
#define AT_HWCAP2 26
#endif
#ifndef HWCAP2_AES
#define HWCAP2_AES (1 << 0)
#endif

static int tgstream_arm32_has_aes(void) {
	unsigned long hw2 = getauxval(AT_HWCAP2);
	return (hw2 & HWCAP2_AES) ? 1 : 0;
}
*/
import "C"

func arm32HardwareAESAvailable() bool {
	return C.tgstream_arm32_has_aes() == 1
}
