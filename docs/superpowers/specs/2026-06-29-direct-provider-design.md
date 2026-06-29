# Direct Provider Design

Date: 2026-06-29
Branch: `develop`

## Goal

Add a canonical `tgstream://` launch format so apps can start playback without
going through Emby. Emby and future integrations should act as source adapters:
they translate their input URL into the same stream model used by direct links,
while `PlayerEntryActivity` remains the only component that launches playback.

## Public URI Format

Initial public contract:

```text
tgstream://play?channel=<id>&messages=<m1-m2-...>
```

Accepted aliases:

- `channel_id` for `channel`
- `message_ids` for `messages`

Optional parameters:

- `title`: URL-encoded display title
- `pos`: resume position in seconds, including fractional values
- `bitrate`: accepted for forward compatibility, but not consumed yet because
  `StreamSpec` has no bitrate field

`messages` may use `-` or `,` as separators. The canonical internal value is
dash-separated.

No path-style alternate form is added for now. Keeping a single public form
avoids multiple permanent contracts and keeps optional parameters simple.

## Architecture

Introduce `DirectLinkCodec` as the shared parser/normalizer for the canonical
direct URI format. It should:

- parse `tgstream://play?...` into `StreamSpec`
- validate required fields and report clear `ProviderException`s
- normalize message ids to dash-separated form
- convert `pos` seconds to milliseconds
- provide a builder for future adapters that need to emit canonical direct URIs

Introduce `DirectProvider` as the provider for already-canonical direct links.
It should:

- handle only `tgstream://play?...`
- call `DirectLinkCodec.parse(uri)`
- return `ReportingContext(kind = "none")`
- perform no network calls

Keep `EmbyProvider` as an adapter, not as a caller of `DirectProvider`. It still
resolves Emby metadata, extracts Telegram stream information from the backing
`.strm`, and returns `ReportingContext(kind = "emby")` so playback reporting is
preserved.

`ProviderRegistry` should include both providers, with `DirectProvider` first:

```kotlin
listOf(DirectProvider(), EmbyProvider())
```

`PlayerEntryActivity` remains the orchestrator:

```text
incoming URI -> ProviderRegistry -> ResolveResult -> Telegram ready -> player
```

## Android Manifest

Add an `ACTION_VIEW` / `BROWSABLE` intent-filter for:

```xml
<data android:scheme="tgstream" android:host="play" />
```

This lets Stremio, Nuvio, browsers, and other apps open canonical direct links.

## Error Handling

Invalid direct links should fail before playback with a clear provider error:

- missing `channel` / `channel_id`
- non-numeric channel
- missing `messages` / `message_ids`
- malformed or empty message ids
- invalid `pos`

Errors continue through the existing `PlayerEntryActivity.fail(...)` path: log,
toast, finish.

## Tests

Add focused unit tests near the existing provider tests:

- parses canonical direct link
- accepts aliases
- normalizes comma-separated messages
- converts fractional `pos` seconds to milliseconds
- preserves URL-decoded `title`
- returns `ReportingContext(kind = "none")`
- rejects missing or malformed required fields
- verifies `ProviderRegistry` selects direct links and still selects Emby links

## Out of Scope

- Direct link path syntax such as `tgstream://play/<channel>/<messages>`
- Stremio provider/reporting
- Nuvio result/progress research
- Manual channel/message entry UI
- Adding bitrate to `StreamSpec`
