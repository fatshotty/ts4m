# Hardware AES on 32-bit ARM

All Telegram media traffic handled by tgstream is encrypted by MTProto. For
streaming, every downloaded byte is decrypted on-device before it reaches the
local HTTP proxy and the video player.

The hot path is AES-256-IGE decrypt inside the gotd MTProto client. On 64-bit
Android builds this is already hardware-accelerated by Go's standard library.
On 32-bit ARM builds (`armeabi-v7a`), Go falls back to software AES even on many
ARMv8 CPUs that physically support AES instructions.

tgstream includes a patched gotd copy that adds a runtime-gated ARMv8 hardware
AES decrypt path for 32-bit devices.

## Why it matters

Some Android TV boxes have ARMv8 CPUs but ship a 32-bit Android OS. Those
devices run the `armeabi-v7a` APK. Without a custom decrypt path, Go's software
AES can become the bottleneck for high-bitrate files.

On a real 32-bit ARMv8 TV box, AES-256-IGE decrypt throughput measured:

| Decrypt path | Throughput | Approximate bitrate |
|---|--:|--:|
| Software AES through Go stdlib | 9.5 MB/s | 76 Mbit/s |
| ARMv8 hardware AES through cgo | 517 MB/s | 4.1 Gbit/s |
| Speedup | ~54x | |

The software result matched the point where a high-bitrate 4K remux stalled:
decrypt alone saturated a CPU core. With hardware AES enabled, crypto is no
longer the limiting factor on that class of device.

## Implementation

The Go engine uses a local patched copy of gotd:

```text
tgengine/third_party/gotd-td/
```

`tgengine/go.mod` points `github.com/gotd/td` to that local copy with a `replace`
directive. The patch adds an ARM-only cgo implementation for AES-256-IGE
decrypt and wires it into gotd's MTProto decrypt path.

Runtime behavior:

- arm64 and x86 builds keep using Go's standard library path.
- 32-bit ARM builds try the ARMv8 hardware AES path when the CPU reports the AES
  extension.
- If the CPU does not report AES support, or if the hardware path rejects the
  input, the code falls back to gotd's normal software decrypt path.
- The patch affects the streaming decrypt hot path. Upload/encrypt paths remain
  on the normal software path because they are not performance-critical here.

The hardware path is one cgo call per decrypted MTProto message, not one call
per block, so the cgo overhead is small compared with the amount of data
decrypted for video streaming.

## Safety checks

MTProto verifies the decrypted message key. A wrong byte in the decrypted
payload fails the message validation and breaks the connection instead of
silently serving corrupt stream data.

The hardware implementation was validated byte-for-byte against the software
IGE path in a standalone benchmark, then verified through successful login and
stream playback on device.

## Device and release notes

The public release currently publishes per-ABI APKs for:

- `arm64-v8a`: modern 64-bit phones and TV boxes. Go already uses hardware AES.
- `armeabi-v7a`: 32-bit Android devices, including ARMv8 boxes with a 32-bit OS.
  This is where the custom hardware AES path matters.

An optional `x86_64` build can be produced for emulator or Redroid-style test
environments, but it is not part of the normal public release set unless
explicitly published.

The app exposes decrypt hardware status in Settings. If hardware decrypt is
unavailable or cannot be verified on a device where it matters, Settings shows a
non-blocking warning so the user can understand possible high-bitrate playback
limits.

## Maintenance notes

The patched gotd copy is intentionally vendored so Android builds are
reproducible. When upgrading gotd, re-apply and re-test the AES-256-IGE decrypt
patch before publishing a release.
