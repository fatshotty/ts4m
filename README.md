# tgstream (ts4m)

Stream video files hosted on **Telegram** channels straight to a player on
Android — no full download first, no re-upload, no quality loss.

## What it does

You log in with your **own** Telegram credentials (api_id/api_hash from
my.telegram.org). The app then plays a file from a channel by downloading it in
parallel chunks over MTProto and serving it to a local player through a
localhost HTTP proxy — so large buffers stay in native memory and never cross
the JNI boundary.

- **Direct playback** of channel media on a TV box or phone.
- **Canonical direct links**: any app can launch
  `tgstream://play?channel=<id>&messages=<m1-m2-...>` with optional `title` and
  `pos` parameters, without Emby or playback reporting.
- **Emby/Jellyfin external-player integration**: the media server launches the
  app with its normal stream URL; tgstream resolves the item metadata and reads a
  `#tgstream/...` directive from the backing `.strm` URL fragment
  (`#tgstream/<channel_id>/<message_ids>`), while the URL before `#` remains
  playable by Emby/Jellyfin/ffmpeg. Server path prefixes such as `/emby` and
  `/jellyfin` are kept for playback progress reporting.
- **Rebuilds split files**: when a large file is stored as several smaller parts
  across multiple Telegram messages, the app stitches them back together **in
  order** and serves one continuous stream.
- **Two player engines**: ExoPlayer/Media3 (default, hardware decode, great
  HDR/Dolby Vision) and libVLC (for containers ExoPlayer can't handle).
- **Tunable buffering** (LOW/MID/HIGH/VHIGH + a DYNAMIC ramp) and a configurable
  number of parallel connections.
- **In-app updater** that pulls new builds from this repo's Releases.

Under the hood it uses the [gotd](https://github.com/gotd/td) MTProto client,
compiled into the Android app via gomobile. The download runs on Telegram's
unthrottled **media endpoint** with N parallel connections on a single account,
plus a warm read-ahead buffer to keep playback smooth across seeks and pauses.

> Architecture details: [docs/architecture.md](docs/architecture.md) ·
> buffering: [docs/buffer-tuning.md](docs/buffer-tuning.md)

## Benchmarks — streaming & decryption

All Telegram traffic is AES-256-IGE encrypted, so every streamed byte is
decrypted on-device. On 64-bit boxes this is hardware-accelerated for free; on
**32-bit (armeabi-v7a) boxes** Go's standard library falls back to *software*
AES — which became the bottleneck for high-bitrate playback.

We added an **ARMv8 hardware-AES** decrypt path (cgo, ARM crypto extension),
runtime-gated with a software fallback and exposed in Settings as a warning when
hardware decrypt is unavailable or cannot be verified. Measured **on a real 32-bit ARMv8 box**,
decrypting AES-256-IGE on one core (output validated byte-for-byte against the
software path):

| Decrypt path (32-bit ARM) | Throughput | |
|---|--:|--:|
| Software AES (Go stdlib, before) | **9.5 MB/s** | 76 Mbit/s |
| ARMv8 hardware AES (cgo, now) | **517 MB/s** | 4.1 Gbit/s |
| **Speedup** | **~54×** | |

The 9.5 MB/s software ceiling matched exactly the point where high-bitrate
streams (~95 Mbps) stalled on weak boxes: decryption alone saturated a CPU core.
With hardware AES the crypto cost at 95 Mbps drops from ~125% of a core to ~2%,
removing crypto as a streaming bottleneck on 32-bit devices. 64-bit and x86
builds are unchanged (their standard library already uses hardware AES).

On the download side, streaming uses the DC's **media endpoint** (unthrottled)
instead of the throttled regular endpoint, with parallel connections on a single
user account — no bots required.

## Install / update

Grab the APK for your device's ABI (arm64-v8a or armeabi-v7a) from the latest
release, or update from inside the app:

**https://github.com/fatshotty/ts4m/releases/latest**
