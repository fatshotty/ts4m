# Media-endpoint parallel download (Phase 1)

Date: 2026-06-21 · Branch: `libvlc`

## Problem

Playback buffers on real devices (TV box, phone): the download can't keep up
with the file bitrate. Throughput on a single user account tops out at
~1.5–7 MB/s and the logs are full of `flood wait 1s/2s`.

## Root cause (verified against tgshelf source)

tgstream downloads `upload.getFile` on **`e.api` — the main/regular connection
of the home DC**. Telegram **throttles sustained GetFile on the regular
endpoint** (those flood waits). tgshelf (Telethon) instead downloads on the
DC's dedicated **`media_only` endpoint** with a sender that reuses the home
auth key — and there it is **not** throttled: the user measured ~10 MB/s per
download on a *single user account*, 20–25 MB/s with two parallel downloads.

So the problem is the **endpoint**, not the account type (bots not required) and
not raw connection count. A secondary issue: after the mobile-churn fix we
collapsed to a single connection (`e.api`), so `conns` wasn't opening parallel
pipes anyway.

## Decision

**Phase 1 (this spec):** download via the **media endpoint** with **N parallel
connections on the same single user account** (`conns`, configurable in
Settings, default 3). No bots.

**Phase 2 (later, optional):** a multi-account/bot worker pool layered on the
same media-endpoint download, only if aggregate >10 MB/s or multi-stream is ever
needed. Out of scope here.

## Design

`tgengine/stream.go` — `mediaClient(dcID, conns)`:
- Build an N-connection invoker to the DC's media endpoint via gotd's
  **`e.client.MediaOnly(ctx, dcID, max)`** (`max = clamp(conns, 1, maxPoolSize)`).
  gotd does not export auth in the pool path (export lives only in `transfer.go`),
  so the home-DC media pool reuses the existing home auth key — no
  ExportAuthorization (which Telegram refuses for the home DC).
- Cache the resulting `*tg.Client` + `CloseInvoker` per DC (as today).
- Fallback: if `MediaOnly` errors, fall back to `client.Pool(max)` (regular
  endpoint — throttled but functional) and log it, so a failure is visible
  rather than silent.

`getChunk`:
- Run the `UploadGetFile` RPCs on the **engine-lived context** (`e.dlCtx`), not
  the per-request context. With a real N-connection pool, cancelling a request
  (seek/abandon) must not tear pooled connections down (that caused the earlier
  conn-id churn spiral). The pipeline already stops *issuing* new chunks when the
  HTTP request ends; the few in-flight chunks (≤ conns) finish and are discarded.
- Keep existing handling: flood-wait backoff, FILE_REFERENCE_EXPIRED refresh,
  transient retry, CDN redirect.

No change to the proxy/streamRange pipeline, the player, or Settings (conns
already exists, 1–16, default 3).

## Why churn shouldn't return

The conn-id churn spiral was triggered by flood waits → stalls → player
reconnect storms → pool reopening connections. The media endpoint removes the
flood waits (the trigger), and `e.dlCtx` means seek-cancellation no longer kills
pooled connections. libVLC also seeks far less aggressively than ExoPlayer did.

## Verification (on emulator + TV box)

1. Log shows the media path is taken (add a clear log line; expect no
   `flood wait` during sustained download).
2. `download: … MB/s` should rise well above the previous ~1.5–7 MB/s
   (target: approach the per-account ~10 MB/s the user saw in tgshelf).
3. Playback starts faster and does not buffer on the TV box.
4. `conn_id` stays bounded (no churn) across seeks.

## Risks / open points

- If `MediaOnly` fails for the home DC, the fallback is the throttled regular
  pool — we'd then need tgshelf's manual approach (a dedicated media sender
  reusing the home auth key). Decide only if the on-device test shows the
  fallback being used.
