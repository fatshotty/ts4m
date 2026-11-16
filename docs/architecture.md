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
downloading the whole file first. The user logs in with their own Telegram
credentials (api_id/api_hash from my.telegram.org); the app downloads the file
in chunks over MTProto and serves it to a local player through a localhost HTTP
proxy. Typical use: TV box / phone.

The canonical direct launch format is
`tgstream://play?channel=<id>&messages=<m1-m2-...>`, with optional `title` and
`pos` query parameters. It starts playback without source-app reporting.

Emby and Jellyfin are also supported through the same source adapter. The media
server launches tgstream as an external player with its normal stream URL;
tgstream resolves the item metadata through the Emby-compatible API and expects
the backing `.strm` media URL to carry a fragment directive such as
`#tgstream/{channel_id}/{ordered-parts-dashed}`. The URL before `#` stays
playable by Emby/Jellyfin/ffmpeg; tgstream uses only the fragment directive and
derives its internal stream id from the Telegram coordinates. Server path
prefixes such as `/emby` and `/jellyfin` stay in the API base URL so playback
progress is reported to the same mounted server. When a large file is stored as
**several smaller parts** across multiple Telegram messages, the engine downloads
them in the given order and serves them as **one continuous stream**.

## The two halves and how they communicate

| Part | Where | Role |
|---|---|---|
| **Android app (Kotlin)** | [app/src/main/java/org/tgstream/app/](../app/src/main/java/org/tgstream/app/) | UI, settings, players, updater |
| **Go engine (`tgengine`)** | [tgengine/](../tgengine/) | MTProto client, streaming pipeline, HTTP proxy |

They talk via **gomobile**: the Go engine is compiled into an AAR
(`app/libs/tgengine.aar`, built with [tgengine/build-aar.sh](../tgengine/build-aar.sh))
and wrapped on the Kotlin side by [Tg.kt](../app/src/main/java/org/tgstream/app/tg/Tg.kt).

**JNI boundary discipline:** only control calls (login, start/stop) cross JNI;
the **video bytes never do** — the Go engine exposes an **in-process HTTP proxy
on 127.0.0.1** and the player connects to it like a normal URL. This keeps the
large buffers in native Go memory. Only gobind-compatible types cross the
boundary (string/int/bool/[]byte/error + callback interfaces); all gotd/tg types
stay internal to the engine.

---

## Go engine (`tgengine/`)

MTProto library: **`github.com/gotd/td` v0.161.0**, replaced by the pinned
[`fatshotty/td` fix](https://github.com/fatshotty/td/commit/96051db424bfdc00f1904242a0bb656d6920ea71)
for ARM32 connection-pool atomic alignment. The fork uses the standard Go
`sync/atomic.Int64` counter so logger layout changes cannot misalign its 64-bit
operations. Session persisted to a file (`session.FileStorage`).

| File | Role |
|---|---|
| [engine.go](../tgengine/engine.go) | `Engine` struct: MTProto + API clients, login state machine, per-DC media pools, document/channel caches, long-lived download context `dlCtx`. Public methods: `Start`, `IsReady`, `SelectLoginMethod`, `CancelLogin`, `SubmitPhone/Code/Password`, `Logout`, `StartProxy`. |
| [stream.go](../tgengine/stream.go) | HTTP proxy + streaming pipeline (see below). |
| [resolve.go](../tgengine/resolve.go) | Channel resolution (Bot-API IDs → raw) and dialog sync to learn access hashes. |
| [log.go](../tgengine/log.go) | Logging bridge to Kotlin (`Logger` implemented on the app side). |
| [sendlog.go](../tgengine/sendlog.go) | Uploads the session log to Telegram "Saved Messages". |
| `github.com/gotd/ige` v0.3.0 | Upstream AES-IGE implementation used by gotd, including the ARMv8 hardware decrypt path for 32-bit ARM Linux and Android. |

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
software-only, so gotd/ige uses ARMv8 hardware AES via cgo (~54× faster) when
the CPU exposes the AES extension. Kotlin exposes this as
`Tg.decryptHardwareStatus`, and Settings shows a non-blocking warning when
hardware decrypt is unavailable or cannot be verified. CDN chunks use AES-CTR
(Go stdlib).

---

## Players and buffering

Two engines, selected in Settings:

- **ExoPlayer/Media3** (default) — [player/PlayerActivityExo.kt](../app/src/main/java/org/tgstream/app/player/PlayerActivityExo.kt).
  D-pad-friendly controller, stats overlay (throughput/buffer/bitrate), scrubbing
  with debounced seek commit, hardware decoder (great HDR/Dolby Vision).
- **libVLC** — [player/PlayerActivity.kt](../app/src/main/java/org/tgstream/app/player/PlayerActivity.kt).
  For containers ExoPlayer can't handle (e.g. large OpenDML AVI); native-memory
  cache; hardware decode is enabled on real devices and disabled on emulators.

**Buffering** ([util/BufferTuning.kt](../app/src/main/java/org/tgstream/app/util/BufferTuning.kt)):
LOW/MID/HIGH/VHIGH tiers (ExoPlayer fixes **MB**, libVLC fixes **seconds**), plus
a **DYNAMIC** mode (ExoPlayer only) that ramps the cap over time
([player/DynamicLoadControl.kt](../app/src/main/java/org/tgstream/app/player/DynamicLoadControl.kt)).
Details and tables in [buffer-tuning.md](buffer-tuning.md).

---

## Settings ([data/Settings.kt](../app/src/main/java/org/tgstream/app/data/Settings.kt), [ui/SettingsActivity.kt](../app/src/main/java/org/tgstream/app/ui/SettingsActivity.kt))

- **Connections per stream** (1–16, default 3)
- **Player engine**: ExoPlayer / libVLC
- **Buffer tier**: LOW/MID/HIGH/VHIGH or DYNAMIC
- **Pre-buffer**: 0 = AUTO (bitrate-scaled) or fixed 5/10/15/20/30 s
- **Telegram credentials**: optional user api_id/api_hash override
- **AES decrypt warning**: shown only when hardware decrypt is unavailable or
  unknown

Stored in SharedPreferences in cleartext (they're the user's own credentials,
never leave the device; no Keystore, for compatibility with odd devices).

---

## Login / auth

[ui/LoginActivity.kt](../app/src/main/java/org/tgstream/app/ui/LoginActivity.kt)
→ [Tg.kt](../app/src/main/java/org/tgstream/app/tg/Tg.kt) → `engine.Start()`.
An existing session is restored automatically. Otherwise the user chooses
QR login or phone/code login. API credentials are still required; the existing
built-in defaults or user overrides apply to both methods.

- **QR:** the Go engine receives login-token updates, exports tokens through
  gotd's QR helper, renews at the server-provided expiry, and handles DC migration
  and optional 2FA. A small transient PNG crosses JNI; the Android screen decodes
  it off the main thread, preserves the white quiet zone, and hides expired codes.
- **Phone:** standard gotd `auth.Flow`, with attempt-local phone/code/password
  inputs and retry for an incorrect 2FA password. Password whitespace is preserved.
- **Switching methods:** cancels and joins the old authentication attempt without
  canceling the engine's long-lived media context. Repeated/stale input is rejected;
  Telegram flood-wait delays survive returning to the selector.
- **Account management:** Settings > Telegram account opens LoginActivity even
  when already connected. It shows connection status and Log out. Login from this
  screen stays in account management; Back returns to Settings. Unsaved Settings
  edits survive this navigation and Activity recreation.
- **Logout:** calls `auth.logOut` for this app's Telegram session, cancels engine,
  proxy, and download work, waits for session writers to exit, then removes
  `filesDir/tg.session`. A new login creates a fresh engine without an app restart.
  Remote failure keeps the local session for retry; local-cleanup failure stays
  disconnected and exposes a cleanup retry. App settings, API credentials, and
  local playback history are retained. Other Telegram devices stay connected.

On successful login the session is persisted to `filesDir/tg.session`, dialogs
are synchronized, and the localhost HTTP proxy starts. Kotlin fences callbacks and
proxy startup by engine generation so an old engine cannot become ready again
after logout. QR images, token URLs, codes, and passwords are never logged or saved.

Account UI device checks: QR scan/renewal/expiry, 2FA with incorrect-password
retry, phone fallback and rapid switching, Back and background/recreation,
Settings entry while connected/disconnected, logout failure/cleanup retry, and
logout followed by login with another account. Verify saved-session restoration
and playback/seek on arm64-v8a and armeabi-v7a when hardware is available.

---

## Invocation / entry point

[PlayerEntryActivity.kt](../app/src/main/java/org/tgstream/app/ui/PlayerEntryActivity.kt)
is the invisible entry point exported for `ACTION_VIEW`. It receives provider
launch URLs, asks [ProviderRegistry.kt](../app/src/main/java/org/tgstream/app/provider/ProviderRegistry.kt)
for a matching provider, resolves the launch into a `StreamSpec`, waits for the
engine to be ready, and launches the chosen player.

The provider registry currently contains:

- [DirectProvider.kt](../app/src/main/java/org/tgstream/app/provider/DirectProvider.kt):
  handles canonical `tgstream://play?...` links and returns
  `ReportingContext(kind = "none")`.
- [EmbyProvider.kt](../app/src/main/java/org/tgstream/app/provider/EmbyProvider.kt):
  extracts the Emby/Jellyfin server base, item id, token, and playback session
  metadata from the launch URL, resolves the item's `.strm` path, and parses a
  `#tgstream/...` fragment directive into the Telegram stream spec. Explicit
  launch start ticks win over the saved item resume position, so "restart" and
  "continue watching" keep their source-app meaning. Playback progress is
  reported back to the same server with the original session metadata.

Emby/Jellyfin reporting uses the provider-resolved user id, item id,
`DeviceId`, `MediaSourceId`, and `PlaySessionId`. Players send
`/Sessions/Playing`, periodic `/Sessions/Playing/Progress`, and
`/Sessions/Playing/Stopped`. Progress heartbeats continue while playback is
paused with `IsPaused=true`, so the source server does not expire the playback
session. When playback reaches the completion threshold (90% of known duration,
or a player-completion event), tgstream also calls the explicit played-state API
(`MarkPlayedItem`) so the source server marks the item watched even if playback
is stopped during credits.

[DirectLinkCodec.kt](../app/src/main/java/org/tgstream/app/provider/DirectLinkCodec.kt)
owns the canonical direct-link parsing and normalization logic used by direct
links and future source adapters.

---

## Updates

In-app updater ([update/Updater.kt](../app/src/main/java/org/tgstream/app/update/Updater.kt)):
checks the `fatshotty/ts4m` GitHub Releases, compares `versionCode`, downloads
the right-ABI APK (arm64-v8a / armeabi-v7a), and hands it to the system
installer.

Publishing via [release.sh](../release.sh): builds the per-ABI APKs, derives
`versionCode = git rev-list --count HEAD` and `versionName = 0.1.<versionCode>`,
and publishes to GitHub (`gh release create`) with both APKs + `version.json`.
The engine AAR must be rebuilt first if the Go code changed.

---
_Last updated: 2026-07-07._

## Android Package and Update Identity

Kotlin sources and the Gradle namespace use `org.tgstream.app`. The installed
`applicationId` deliberately remains `net.fatshotty.tgstream`; changing it would
create a different app instead of updating existing installations. The
FileProvider authority continues to derive from this application ID.

Manifest activity aliases retain the historical launcher and external-player
component names so saved Android shortcuts and explicit app integrations keep
working. Release APKs must retain the established signing certificate, increase
`versionCode`, and keep the updater asset names `tgstream-arm64-v8a.apk`,
`tgstream-armeabi-v7a.apk`, and `version.json`.
