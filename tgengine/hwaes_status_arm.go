//go:build arm && cgo && (linux || android)
// +build arm
// +build cgo
// +build linux android

package tgengine

/*
#cgo LDFLAGS: -ldl
#include <dlfcn.h>

#ifndef AT_HWCAP2
#define AT_HWCAP2 26
#endif
#ifndef HWCAP2_AES
#define HWCAP2_AES (1 << 0)
#endif

static int tgstream_arm32_has_aes(void) {
	typedef unsigned long (*getauxval_fn)(unsigned long);
	const char *libc_name =
#if defined(__ANDROID__)
		"libc.so";
#else
		"libc.so.6";
#endif
	void *libc = dlopen(libc_name, RTLD_LAZY | RTLD_LOCAL);
	if (libc == NULL) return 0;
	getauxval_fn getauxval = (getauxval_fn)dlsym(libc, "getauxval");
	if (getauxval == NULL) {
		dlclose(libc);
		return 0;
	}
	unsigned long hw2 = getauxval(AT_HWCAP2);
	dlclose(libc);
	return (hw2 & HWCAP2_AES) ? 1 : 0;
}
*/
import "C"

func arm32HardwareAESAvailable() bool {
	return C.tgstream_arm32_has_aes() == 1
}
