# Playback buffer — reference

How the buffer levels (LOW / MID / HIGH / VHIGH / DYNAMIC) are computed and the
MB ↔ seconds conversion tables. Applies to **both** player engines.

Source of the values: [`BufferTuning.kt`](../app/src/main/java/net/fatshotty/tgstream/util/BufferTuning.kt)
→ `exoBytes()` and `vlcCachingMs()`. User choice in Settings
([`SettingsActivity.kt`](../app/src/main/java/net/fatshotty/tgstream/ui/SettingsActivity.kt)),
default **HIGH** (reproduces the smooth v0.1.20 behaviour on typical TV boxes).

## Principle

The two engines pin the buffer to different quantities:

- **ExoPlayer** → pins the **MB** (the buffer lives on the Java heap, so it must
  be capped in bytes). The seconds depend on the file's bitrate.
- **libVLC** → pins the **seconds** (`network-caching`, native memory; this is
  also the startup latency and the refill wait after a seek). The MB depend on
  the bitrate.

Conversion:

```
seconds = MB × 8 / Mbps
MB      = seconds × Mbps / 8
```

## The values are FIXED (not device-dependent)

The tier is an explicit user choice; the MB (Exo) and the seconds (VLC) are
constants, identical on every device. The old RAM-based `autoTier()` logic is
**no longer used** (dead code, kept only for reference).

> ⚠️ Practical exception: **VHIGH (384 MB)** requires `largeHeap` + a ~512 MB
> heap. On low-RAM hardware it can cause `OutOfMemoryError`: the value is not
> reduced automatically, so avoid it manually on weak devices.

## ExoPlayer — fixed MB, computed seconds

Byte cap per tier. There is also a separate time cap (max buffer = 300s) that
only binds at low bitrates.

| Tier  | MB (fixed) | @20 Mb/s | @50 Mb/s | @80 Mb/s | @120 Mb/s |
|-------|-----------:|---------:|---------:|---------:|----------:|
| LOW   |  48 MB | 19.2 s | 7.7 s  | 4.8 s  | 3.2 s  |
| MID   | 128 MB | 51.2 s | 20.5 s | 12.8 s | 8.5 s  |
| HIGH  | 256 MB | 102 s  | 41 s   | 25.6 s | 17.1 s |
| VHIGH | 384 MB | 154 s* | 61 s   | 38.4 s | 25.6 s |

\* at low bitrates the time cap (300s) may kick in first.

## libVLC — fixed seconds, computed MB

`network-caching` per tier (= also startup latency and post-seek wait).

| Tier  | Seconds (fixed) | @20 Mb/s | @50 Mb/s | @80 Mb/s | @120 Mb/s |
|-------|----------------:|---------:|---------:|---------:|----------:|
| LOW   |  3 s  | 7.5 MB  | 18.75 MB | 30 MB  | 45 MB  |
| MID   |  6 s  | 15 MB   | 37.5 MB  | 60 MB  | 90 MB  |
| HIGH  | 10 s  | 25 MB   | 62.5 MB  | 100 MB | 150 MB |
| VHIGH | 15 s  | 37.5 MB | 93.75 MB | 150 MB | 225 MB |

## DYNAMIC (ExoPlayer only) — automatic ramp over time

The byte cap grows with elapsed playback time, with an anti-stall floor that
always keeps ≥15 s buffered. Implementation:
[`DynamicLoadControl.kt`](../app/src/main/java/net/fatshotty/tgstream/player/DynamicLoadControl.kt).

| Playback time | Tier  | MB     | @80 Mb/s |
|---------------|-------|-------:|---------:|
| 0–10 s        | LOW   |  48 MB | ~4.8 s  |
| 10–20 s       | HIGH  | 256 MB | ~25.6 s |
| 20 s +        | VHIGH | 384 MB | ~38.4 s |

On **libVLC** "dynamic" does not ramp (the cache is fixed at init and can't be
resized at runtime): it falls back to the fixed **MID** tier (6 s).

## Related notes

- **ExoPlayer restart:** after a rebuffer it resumes with ~1.5 s of buffer (a
  deliberate choice, for fast restart). The large buffer is obtained by choosing
  a high tier or DYNAMIC.
- **Proxy prefetch:** independent of the player buffer, the Go proxy keeps
  ~64 MiB of "warm" pre-downloaded video to absorb ExoPlayer's pause/resume
  cycles (see `readAhead` in [`stream.go`](../tgengine/stream.go)).

---
_Last updated: 2026-06-25._
