# tgstream (ts4m) — architecture overview

A summary of how the app is built: what it does, its two halves (Android/Kotlin
app + Go engine), how they talk, and the main subsystems (streaming, players,
buffering, login, update).

> For specifics see the dedicated docs: playback buffers
> ([buffer-tuning.md](buffer-tuning.md)), media-endpoint download
> ([specs/2026-06-21-media-endpoint-download-design.md](superpowers/specs/2026-06-21-media-endpoint-download-design.md)),
> 32-bit hardware AES
> ([specs/2026-06-24-armv8-hardware-aes-32bit.md](superpowers/specs/2026-06-24-armv8-hardware-aes-32bit.md)).

---

## What it does

Streams video files hosted on Telegram channels **directly**, without
downloading the whole file first. Login uses Telegram api_id/api_hash — either
the app's built-in defaults or the user's own (from my.telegram.org); the app
downloads the file in chunks over MTProto and serves it to a local player
through a localhost HTTP proxy. Typical use: TV box / phone.

The app is launched with a link following the pattern
`/{channel_id}/{ordered-parts-dashed}` — the channel ID followed by the file's
part message IDs, in order, dash-separated (e.g. `/1234567890/15-16-17`). When a
large file is stored as **several smaller parts** across multiple Telegram
messages, the engine downloads them in the given order and serves them as **one
continuous stream**.

## The two halves and how they communicate

| Part | Where | Role |
|---|---|---|
| **Android app (Kotlin)** | [app/src/main/java/net/fatshotty/tgstream/](../app/src/main/java/net/fatshotty/tgstream/) | UI, settings, players, updater |
| **Go engine (`tgengine`)** | [tgengine/](../tgengine/) | MTProto client, streaming pipeline, HTTP proxy |

They talk via **gomobile**: the Go engine is compiled into an AAR
(`app/libs/tgengine.aar`, built with [tgengine/build-aar.sh](../tgengine/build-aar.sh))
and wrapped on the Kotlin side by [Tg.kt](../app/src/main/java/net/fatshotty/tgstream/tg/Tg.kt).

**JNI boundary discipline:** only control calls (login, start/stop) cross JNI;
the **video bytes never do** — the Go engine exposes an **in-process HTTP proxy
on 127.0.0.1** and the player connects to it like a normal URL. This keeps the
large buffers in native Go memory. Only gobind-compatible types cross the
boundary (string/int/bool/[]byte/error + callback interfaces); all gotd/tg types
stay internal to the engine.

---

## Go engine (`tgengine/`)

MTProto library: **`github.com/gotd/td` v0.111.0**. Session persisted to a file
(`session.FileStorage`).

| File | Role |
|---|---|
| [engine.go](../tgengine/engine.go) | `Engine` struct: MTProto + API clients, login state machine, per-DC media pools, document/channel caches, long-lived download context `dlCtx`. Public methods: `Start`, `IsReady`, `SubmitPhone/Code/Password`, `SetCacheDir`, `StartProxy`. |
| [stream.go](../tgengine/stream.go) | HTTP proxy + streaming pipeline (see below). |
| [diskcache.go](../tgengine/diskcache.go) | Optional on-disk write-ahead circular ring (one stream at a time). |
| [resolve.go](../tgengine/resolve.go) | Channel resolution (Bot-API IDs → raw) and dialog sync to learn access hashes. |
| [log.go](../tgengine/log.go) | Logging bridge to Kotlin (`Logger` implemented on the app side). |
| [sendlog.go](../tgengine/sendlog.go) | Uploads the session log to Telegram "Saved Messages". |
| [third_party/gotd-td/](../tgengine/third_party/gotd-td/) | Local patched copy of gotd/td (ARMv8 hardware AES on the IGE decrypt for 32-bit boxes; see the dedicated spec). Wired in via `replace` in [go.mod](../tgengine/go.mod). |

### Streaming pipeline ([stream.go](../tgengine/stream.go))

1. **HTTP endpoint** `GET/HEAD /stream?channel=<id>&messages=<m1-m2-…>&conns=<n>`,
   with Range support (seek).
2. **Parallel chunked download**: 1 MiB chunks aligned to `upload.getFile`
   boundaries, up to `conns` in-flight RPCs (semaphore).
3. **Media endpoint**: downloads use the DC's `MediaOnly` pool (unthrottled,
   ~10 MB/s per account) instead of the regular endpoint (throttled); automatic
   fallback to the regular pool if `MediaOnly` fails. Per-DC pools are cached and
   only ever grow, never torn down mid-stream.
4. **Read-ahead / warm prefetch**: keeps up to ~128 MiB (8 × conns) pre-fetched
   to absorb the player's buffer pause/resume cycles without cooling the workers.
5. **Robustness**: flood-wait backoff (up to 6 retries), refresh of expired
   `FILE_REFERENCE`s, transient retries, and **CDN redirect** handling (CDN
   chunks decrypted with AES-CTR).
6. **Long-lived context** `dlCtx`: download RPCs run on a context that is not
   cancelled per-request, so a seek doesn't tear down the pooled connections.

### Decryption

All MTProto traffic is AES-256-IGE (handled by gotd). On **arm64** builds Go
uses the stdlib's hardware AES; on **armeabi-v7a (32-bit)** the stdlib is
software-only, so in the patched gotd the IGE decrypt uses ARMv8 hardware AES via
cgo (~54× faster). CDN chunks use AES-CTR (Go stdlib).

---

## Players and buffering

Two engines, selected in Settings:

- **ExoPlayer/Media3** (default) — [player/PlayerActivityExo.kt](../app/src/main/java/net/fatshotty/tgstream/player/PlayerActivityExo.kt).
  D-pad-friendly controller, stats overlay (throughput/buffer/bitrate), scrubbing
  with debounced seek commit, hardware decoder (great HDR/Dolby Vision).
- **libVLC** — [player/PlayerActivity.kt](../app/src/main/java/net/fatshotty/tgstream/player/PlayerActivity.kt).
  For containers ExoPlayer can't handle (e.g. large OpenDML AVI); native-memory
  cache; hardware decode on real devices.

On exit both players return an **MX Player-style result intent** (`end_by` +
`position`/`duration` in ms) so an external launcher (e.g. Stremio/Nuvio) can
update its Continue Watching; an inbound MX `position` extra is honored to resume.

**Buffering** ([util/BufferTuning.kt](../app/src/main/java/net/fatshotty/tgstream/util/BufferTuning.kt)):
LOW/MID/HIGH/VHIGH tiers (ExoPlayer fixes **MB**, libVLC fixes **seconds**), plus
a **DYNAMIC** mode (ExoPlayer only) that ramps the cap over time
([player/DynamicLoadControl.kt](../app/src/main/java/net/fatshotty/tgstream/player/DynamicLoadControl.kt)).
Details and tables in [buffer-tuning.md](buffer-tuning.md).

---

## Settings ([data/Settings.kt](../app/src/main/java/net/fatshotty/tgstream/data/Settings.kt), [ui/SettingsActivity.kt](../app/src/main/java/net/fatshotty/tgstream/ui/SettingsActivity.kt))

- **Max connections per stream** (1–16, default 3) — a ceiling; the engine
  auto-scales the actual count by file size (~1 connection per 20 GiB)
- **Player engine**: ExoPlayer / libVLC
- **Buffer tier**: LOW/MID/HIGH/VHIGH or DYNAMIC
- **Pre-buffer**: 0 = AUTO (bitrate-scaled) or fixed 2/5/10/15/20/30 s
- **Telegram credentials** (advanced): leave empty to use the app's built-in
  defaults, or enter your own (which take priority)

User-entered credentials are stored in SharedPreferences in cleartext (they're
the user's own, never leave the device; no Keystore, for compatibility with odd
devices). The app's built-in defaults instead live XOR-obfuscated inside the Go
engine and are injected at build time via `-ldflags` (see
[tgengine/credentials.go](../tgengine/credentials.go)), so no plaintext ships in
git or in the APK's Java/DEX.

---

## Login / auth

[ui/LoginActivity.kt](../app/src/main/java/net/fatshotty/tgstream/ui/LoginActivity.kt)
→ [Tg.kt](../app/src/main/java/net/fatshotty/tgstream/tg/Tg.kt) → `engine.Start()`.
Standard gotd `auth.Flow`: phone (E.164) → SMS code → optional 2FA password, with
values handed from Kotlin to Go over buffered channels. On success the session is
persisted to a file (`filesDir/tg.session`); subsequent launches reconnect
without re-logging in, and the HTTP proxy starts.

---

## Invocation / entry point

[PlayerEntryActivity.kt](../app/src/main/java/net/fatshotty/tgstream/ui/PlayerEntryActivity.kt)
is the invisible entry point exported for `ACTION_VIEW`, so the app can be
launched from any other app. The link follows the pattern
`/{channel_id}/{ordered-parts-dashed}` — the channel ID followed by the file's
part message IDs, in order, dash-separated (e.g. `/1234567890/15-16-17`). The
entry point parses it into a `StreamSpec` (channel + ordered message IDs), waits
for the engine to be ready, and launches the chosen player. When the file is
split across several messages, the parts are streamed back-to-back as one
continuous file.

---

## Updates

In-app updater ([update/Updater.kt](../app/src/main/java/net/fatshotty/tgstream/update/Updater.kt)):
checks the `fatshotty/ts4m` GitHub Releases, compares `versionCode`, downloads
the right-ABI APK (arm64-v8a / armeabi-v7a), and hands it to the system
installer.

Publishing via [release.sh](../release.sh): builds the per-ABI APKs, derives
`versionCode = git rev-list --count HEAD` and `versionName = 0.1.<versionCode>`,
and publishes to GitHub (`gh release create`) with both APKs + `version.json`.
The engine AAR must be rebuilt first if the Go code changed.

---
_Last updated: 2026-06-25._
