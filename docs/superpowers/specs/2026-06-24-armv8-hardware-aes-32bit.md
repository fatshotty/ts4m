# ARMv8 hardware AES for MTProto decrypt on 32-bit ARM

Date: 2026-06-24 · Branch: `master` · Shipped: v0.1.34

## Problem

High-bitrate playback (~95 Mbps 4K remux) stalls on a cheap Leelbox but plays
fine on an Nvidia Shield, same app/account/settings. The download collapses to
~9 MB/s while the film needs ~11.9 MB/s → buffer drains → stall. Earlier work
attributed this to a generic "weak box CPU = decode + crypto contention" limit
and concluded it wasn't fixable in code (see `high-bitrate-weak-box-cpu-limit`
memory). This spec revisits the **crypto half** and shows it *is* fixable.

## Root cause (measured on the box)

The Leelbox is an **ARMv8** SoC (cpuinfo Features: `aes pmull sha1 sha2`) but
runs a **32-bit OS** (`ro.product.cpu.abilist64` empty, model `ARMv8 … (v8l)`),
so the app runs the **armeabi-v7a** APK.

**Go's `crypto/aes` has hardware-accelerated assembly only for arm64/amd64/
ppc64/s390x — NOT for `GOARCH=arm` (32-bit).** So on the v7a build Go falls back
to software AES *even though the CPU has the AES instructions*. The entire
MTProto stream is AES-256-IGE-decrypted in gotd at
`crypto/cipher_decrypt.go:decryptMessage` (`aes.NewCipher` + `ige.DecryptBlocks`),
per byte.

A standalone PoC benchmark run **on the box** (cgo + ARMv8 AArch32 crypto
intrinsics `vaesdq`/`vaesimcq`, output validated byte-for-byte vs the software
IGE):

| Path | Throughput (single core) |
|---|---|
| Software IGE (Go `crypto/aes`, current) | **9.5 MB/s (76 Mbit/s)** |
| Hardware IGE (ARMv8 crypto ext via cgo) | **517 MB/s (4.1 Gbit/s)** |
| **Speedup** | **54.75×** |

The 9.5 MB/s software ceiling **exactly matches** the observed "download
collapses to ~9 MB/s" symptom → crypto was the binding constraint on 32-bit
boxes, not merely co-equal with video decode.

## Decision

Bring hardware AES to the 32-bit build via **cgo**, wired into gotd's IGE
decrypt hot path through a **local `replace` of a trimmed gotd/td copy** under
`third_party/`. Runtime-gated with software fallback. arm64/x86 untouched (their
stdlib already uses hardware AES). Decrypt-only (the streaming hot path);
encrypt/uploads keep software AES (low volume).

Chosen over the alternatives (forked gotd repo on GitHub; build-time `-overlay`)
for a self-contained, reproducible build with no external repo dependency.

## Design

- `tgengine/third_party/gotd-td/` — local copy of gotd/td v0.111.0, trimmed
  115M→27M (removed `_fuzz`, `testdata`, `*_test.go`, `examples`, and the unused
  `tdapi`/TDLib bindings). `replace github.com/gotd/td => ./third_party/gotd-td`
  in `tgengine/go.mod`.
- `crypto/hwaes_ige_arm.c` — AES-256 key schedule + IGE decrypt using
  `vaesdq_u8`/`vaesimcq_u8`. gotd's IGE convention: `c=iv[:16]` (prev
  ciphertext), `m=iv[16:]` (prev plaintext); `P_i = AESdec(C_i ^ P_{i-1}) ^
  C_{i-1}`. Runtime gate: `getauxval(AT_HWCAP2) & HWCAP2_AES`; returns -1 if
  absent.
- `crypto/hwaes_arm.go` (`//go:build arm`) — cgo wrapper with
  `#cgo CFLAGS: -march=armv8-a+crypto -mfpu=crypto-neon-fp-armv8`.
  `crypto/hwaes_stub.go` (`//go:build !arm`) — returns false (no-op).
- `crypto/cipher_decrypt.go` — patched to try `hwIGEDecrypt(...)` first, fall
  back to `aes.NewCipher` + `ige.DecryptBlocks` on false (non-arm, or no
  HWCAP2_AES, or bad input). One cgo call per ~1 MiB message → overhead
  negligible.

## Verification

1. **Correctness** — PoC on the box: hardware output == software output,
   byte-for-byte, over 16 MiB random data.
2. **Safety net** — gotd verifies the SHA-256 `msg_key` on every decrypted
   message (`cipher_decrypt.go:34-37`); a single wrong byte would kill the
   connection. A successful login + stream is itself a correctness proof.
3. **Build** — AAR builds via gomobile (exit 0). The packaged
   `lib/armeabi-v7a/libgojni.so` contains the hardware AES (14 `aesd.8` +
   26 `aesimc.8` in `aes256_ige_decrypt`, confirmed with
   `llvm-objdump --mattr=+crypto`; the default v7 decoder hides them).
4. **On-box playback (pending)** — play the ~95 Mbps file that previously
   stalled; expect the download to no longer collapse on CPU.

## Risks / open points

- **Maintenance**: the vendored gotd/td copy is pinned; the one-file decrypt
  patch must be re-applied on a gotd upgrade.
- **Decode half remains**: this removes crypto as a bottleneck, not video
  decode. If the box can't HW-decode the bitrate, it can still stall — but then
  the residual limit is the decoder, not crypto.
- **Encrypt path** (uploads) still uses software AES; fine for now (tiny
  volume), could get the same treatment if ever needed.
- **Best non-code fix** stays a 64-bit OS/box: there Go uses hardware AES
  automatically and no fork is needed.

PoC sources: session scratchpad `bench/` (standalone benchmark).
