# AGENTS.md

Guidance for AI/coding agents working in this repository.

## Project Overview

`tgstream` is an Android app that streams Telegram-hosted video files directly to
an Android player. The app has two main parts:

- `app/`: Android/Kotlin app (`net.fatshotty.tgstream`) for UI, settings,
  providers, players, updater, and the Kotlin wrapper around the Go engine.
- `tgengine/`: Go MTProto engine compiled into `app/libs/tgengine.aar` with
  `gomobile bind`. It owns Telegram login/session, media fetching, the local
  HTTP proxy, parallel chunk downloads, and the patched gotd fork.

The video byte stream must stay out of JNI. Kotlin sends control calls to the Go
engine; players read bytes from the engine's local HTTP proxy.

## Language Policy

- Keep comments, logs, diagnostics, exception messages, developer-facing strings,
  release notes, and documentation in English.
- If existing Italian comments or logs are found, call them out before final
  confirmation instead of silently preserving them.
- User-facing copy should also be English unless a task explicitly asks to keep
  or introduce another language.

## Important Paths

- `app/src/main/java/net/fatshotty/tgstream/tg/Tg.kt`: Kotlin facade for the
  gomobile-generated `tgengine` API.
- `app/src/main/java/net/fatshotty/tgstream/ui/PlayerEntryActivity.kt`:
  invisible external-player entry point.
- `app/src/main/java/net/fatshotty/tgstream/provider/`: source-provider
  integrations. Emby is the current provider.
- `app/src/main/java/net/fatshotty/tgstream/player/`: ExoPlayer and libVLC
  activities plus dynamic ExoPlayer load control.
- `app/src/main/java/net/fatshotty/tgstream/data/Settings.kt`: persisted app
  settings.
- `tgengine/engine.go`: engine lifecycle, login, and media clients.
- `tgengine/stream.go`: `/stream` HTTP endpoint, range handling, dynamic
  connection selection, read-ahead pipeline.
- `tgengine/third_party/gotd-td/`: local patched gotd copy, used through the
  `replace` directive in `tgengine/go.mod`.
- `docs/`: architecture and tuning notes. Treat code as source of truth when
  docs conflict with current implementation.
- `TODO.local.md`: local ignored backlog. Do not commit it.

## Build And Test Commands

Run Android commands from the repository root:

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

Run Go commands from `tgengine/`:

```bash
go test ./...
go build ./...
```

After changing Go engine code that is used by Android, regenerate the AAR:

```bash
bash tgengine/build-aar.sh
```

Then rebuild the Android app. `app/libs/tgengine.aar` is generated and ignored by
Git.

Release publishing is handled by:

```bash
./release.sh "release notes"
```

This requires `gh` authentication and should only be run when explicitly asked.

## Environment Notes

- Android build uses AGP/Kotlin versions in `gradle/libs.versions.toml`.
- Java 17+ is required for normal Android builds; `release.sh` forces Android
  Studio's bundled JBR.
- `tgengine/build-aar.sh` expects Go, `gomobile`/`gobind`, Android SDK, and NDK.
- `local.properties`, build outputs, APKs/AABs, logs, sessions, and generated
  AARs are ignored.

## Secrets And Local Files

Never commit Telegram session files, credentials, local notes, logs, APKs, or
generated AARs.

Important ignored files include:

- `credentials.local` and `**/credentials.local`
- `*.session`, `**/spike-session.json`, `**/*-session.json`
- `TODO.local.md` and other `*.local.md`
- `local.properties`
- `logapp*.txt`
- `app/libs/*.aar`

`tgengine/credentials.local.example` is safe as a template. Plain credentials
belong only in ignored local files or environment variables.

## Architecture Constraints

- Do not move large media buffers through Kotlin/JNI. Keep streaming through the
  Go HTTP proxy.
- Preserve HTTP range support in `tgengine/stream.go`; players rely on it for
  seek and resume.
- The `conns` query parameter is the user's maximum. The engine chooses the
  actual connection count from file size and caps it at `maxPoolSize`.
- The Go proxy is localhost-only. Do not expose it on LAN unless request
  protection is reintroduced.
- The media client pools in `tgengine` are shared per DC. Be careful with
  cancellation and pool resizing; request cancellation must not tear down the
  long-lived download context.
- The local gotd fork is intentional. Changes under `tgengine/third_party/gotd-td`
  affect Telegram transport/decryption behavior and should be tested on the
  relevant ABI when possible.
- If changing `tgengine/stream.go`, bump `buildTag` so logs identify the running
  engine build.

## Provider And Playback Notes

- Current provider registry contains Emby only.
- Emby launch URLs are parsed in `EmbyProvider`; the resolved `.strm` media path
  is expected to contain `/download/{id}/{channel_id}/{message_ids}`.
- `PlayerEntryActivity` must fail with a toast and finish instead of crashing.
- `PlayerEntryActivity` stays alive behind the player to relay MX-style result
  intents back to the launcher.
- ExoPlayer is the default engine; libVLC is the alternate engine.
- Buffer modes are `low`, `mid`, `high`, `vhigh`, and `dynamic`; dynamic ramping
  is ExoPlayer-specific.

## Coding Style

- Follow the existing simple Kotlin/Android style: activities are mostly
  programmatic UI, no Compose.
- Keep gomobile-exported Go APIs limited to gomobile-compatible types.
- Prefer small, targeted changes. Avoid broad refactors unless needed for the
  requested behavior.
- Add focused tests near changed behavior when practical. Existing unit coverage
  includes provider parsing in `app/src/test/...`.
- Use ASCII unless editing a file that already intentionally uses non-ASCII.

## Verification Checklist

Before handing off substantial changes, run the narrowest relevant checks:

- Kotlin/provider/UI-only change: `./gradlew :app:testDebugUnitTest` and, when
  build impact is possible, `./gradlew :app:assembleDebug`.
- Go engine change: `cd tgengine && go test ./...`; if Android uses the change,
  run `bash tgengine/build-aar.sh` and then an Android build.
- Release/updater changes: verify ABI split APK names and `version.json`
  expectations before publishing.

Do not commit changes unless the user explicitly asks for a commit.
