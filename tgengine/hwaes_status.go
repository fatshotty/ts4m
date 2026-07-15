package tgengine

import "runtime"

const (
	DecryptHardwareNative           = "native"
	DecryptHardwareAcceleratedArm32 = "accelerated-arm32"
	DecryptHardwareSoftware         = "software"
	DecryptHardwareUnknown          = "unknown"
)

// DecryptHardwareStatus reports which AES decrypt path this build can use.
func DecryptHardwareStatus() string {
	return decryptHardwareStatusForRuntime(runtime.GOARCH, arm32HardwareAESAvailable())
}

func decryptHardwareStatusForRuntime(arch string, arm32HW bool) string {
	switch arch {
	case "arm64", "amd64":
		return DecryptHardwareNative
	case "arm":
		if arm32HW {
			return DecryptHardwareAcceleratedArm32
		}
		return DecryptHardwareSoftware
	default:
		return DecryptHardwareUnknown
	}
}
