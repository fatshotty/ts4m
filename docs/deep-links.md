# Deep links and provider integration

This page documents the public launch formats that other apps can use to start
tgstream playback, and the way Emby/Jellyfin `.strm` items should carry
Telegram stream metadata.

## Direct links

Use the canonical direct link when the caller already knows the Telegram channel
and ordered message parts:

```text
tgstream://play?channel=<id>&messages=<m1-m2-...>
```

Required parameters:

- `channel`: numeric Telegram channel id. Bot-API style ids such as `-100...`
  are accepted and normalized internally. Alias: `channel_id`.
- `messages`: ordered Telegram message ids. Use `-` between parts; `,` is also
  accepted and normalized to `-`. Alias: `message_ids`.

Optional parameters:

- `title`: URL-encoded display title.
- `pos`: resume position in seconds. Fractional values are accepted, for
  example `90.5`.
- `bitrate`: accepted for forward compatibility in kbps. The current app
  version does not require it for playback.

Example:

```text
tgstream://play?channel=-1001234567890&messages=101-102-103&title=Movie%20Night&pos=90.5
```

Direct links resolve to a source provider with `kind = "none"`. That means
tgstream starts playback but does not send playback progress or watched-state
updates back to the caller.

## Android intent contract

The Android app exposes an `ACTION_VIEW` / `BROWSABLE` intent filter for:

```xml
<data android:scheme="tgstream" android:host="play" />
```

Any Android app, browser, launcher, or catalog app can open the canonical
`tgstream://play?...` URI through the normal Android intent resolver.

## Emby and Jellyfin provider links

When tgstream is launched from Emby or Jellyfin as an external player, the
incoming URI is not the canonical `tgstream://` link. The app first recognizes
the source provider from the launch URL and extracts the provider metadata it
needs for playback reporting:

- server base URL, including path prefixes such as `/emby` or `/jellyfin`
- item id
- access token
- device id
- media source id
- play session id
- explicit start position, when the source app sends one

tgstream then resolves the media item through the Emby-compatible API and reads
the backing `.strm` URL. That `.strm` URL must carry the Telegram coordinates in
a `#tgstream/...` fragment:

```text
https://media.example.test/library/movie.mkv#tgstream/<channel_id>/<message_ids>
```

Example:

```text
https://files.example.test/movies/movie.mkv#tgstream/-1001234567890/101-102-103
```

The part before `#` remains a normal media URL and should stay playable by the
source server, ffmpeg, or other tooling. tgstream only consumes the fragment
directive after `#` to build its internal Telegram stream spec.

The supported fragment format is:

```text
#tgstream/<channel_id>/<message_ids>
```

Rules:

- `<channel_id>` follows the same rules as the direct `channel` parameter.
- `<message_ids>` follows the same rules as the direct `messages` parameter.
- Message ids must remain ordered. tgstream stitches the Telegram parts together
  in the order provided.
- Source-server path prefixes before `#` are preserved for API/reporting
  purposes and are not used as tgstream stream ids.

## Resume and reporting behavior

For Emby/Jellyfin launches, explicit start ticks from the launch URI win over
the saved item resume position. This preserves the source app's distinction
between "restart" and "continue watching".

During playback, tgstream reports progress back through the provider metadata
from the launch URL. It sends:

- `/Sessions/Playing`
- periodic `/Sessions/Playing/Progress`
- `/Sessions/Playing/Stopped`
- an explicit played-state request near completion

Progress heartbeats continue while playback is paused with `IsPaused=true`, so
the source server does not expire the playback session during a pause. When the
player reaches the completion threshold, tgstream also marks the source item as
played so Emby/Jellyfin can update watched state even if playback stops during
credits.

## Invalid links

Invalid direct links or invalid `.strm` fragments fail before playback starts.
Typical validation errors are:

- missing channel
- non-numeric channel
- missing message ids
- malformed or empty message ids
- invalid resume position

The app logs the provider error, shows a toast, and closes the invisible entry
activity instead of crashing.
