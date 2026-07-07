# Emby Playback Reporting Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep Emby/Jellyfin playback sessions alive while paused and mark items watched when playback reaches the credits/end threshold.

**Architecture:** Preserve the existing provider/player/reporting split. `EmbyProvider` resolves the user id once and passes it through `ReportingContext`; players decide when playback is effectively complete; `EmbyReporter` owns the HTTP calls to session and played-state APIs.

**Tech Stack:** Android/Kotlin, Media3 ExoPlayer, libVLC, OkHttp, JUnit.

---

### Task 1: Completion Threshold

**Files:**
- Create: `app/src/main/java/net/fatshotty/tgstream/player/PlaybackCompletion.kt`
- Test: `app/src/test/java/net/fatshotty/tgstream/player/PlaybackCompletionTest.kt`

- [x] **Step 1: Write the failing test**

Add tests proving completed playback is watched, 90% playback is watched, 89.999% playback is not watched, and unknown duration is not watched unless the player explicitly completed.

- [x] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests net.fatshotty.tgstream.player.PlaybackCompletionTest`

Expected: FAIL because `PlaybackCompletion` is unresolved.

- [x] **Step 3: Write minimal implementation**

Create `PlaybackCompletion.shouldMarkWatched(positionMs, durationMs, completed)` with a 90% threshold and explicit-completion override.

- [x] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests net.fatshotty.tgstream.player.PlaybackCompletionTest`

Expected: PASS.

### Task 2: Emby Reporting Context

**Files:**
- Modify: `app/src/main/java/net/fatshotty/tgstream/provider/Provider.kt`
- Modify: `app/src/main/java/net/fatshotty/tgstream/provider/EmbyProvider.kt`
- Modify: `app/src/main/java/net/fatshotty/tgstream/ui/PlayerEntryActivity.kt`
- Modify: `app/src/main/java/net/fatshotty/tgstream/player/PlayerActivity.kt`
- Modify: `app/src/main/java/net/fatshotty/tgstream/player/PlayerActivityExo.kt`

- [x] **Step 1: Pass `userId` through reporting**

Add `userId` to `ReportingContext`, set it from `EmbyProvider`, place it in the player intent, and pass it to `EmbyReporter`.

### Task 3: Pause Heartbeats And Watched State

**Files:**
- Modify: `app/src/main/java/net/fatshotty/tgstream/emby/EmbyReporter.kt`
- Modify: `app/src/main/java/net/fatshotty/tgstream/player/PlayerActivity.kt`
- Modify: `app/src/main/java/net/fatshotty/tgstream/player/PlayerActivityExo.kt`

- [x] **Step 1: Keep session progress alive while paused**

Change both player progress loops to keep sending `/Sessions/Playing/Progress` every 10 seconds after playback starts, with `IsPaused=true` when the player is paused.

- [x] **Step 2: Add explicit mark-played**

Add `EmbyReporter.markPlayed()`. Try Emby-style `POST /Users/{userId}/PlayedItems/{itemId}` first, then Jellyfin-style `POST /UserPlayedItems/{itemId}?userId={userId}` as fallback.

- [x] **Step 3: Mark watched near completion**

After `/Sessions/Playing/Stopped`, call `markPlayed()` only if `PlaybackCompletion.shouldMarkWatched(...)` returns true.

### Task 4: Verification

**Files:**
- Verify only.

- [x] **Step 1: Run focused unit tests**

Run: `./gradlew :app:testDebugUnitTest --tests net.fatshotty.tgstream.player.PlaybackCompletionTest --tests net.fatshotty.tgstream.provider.EmbyProviderTest`

- [x] **Step 2: Run full unit tests**

Run: `./gradlew :app:testDebugUnitTest`

- [x] **Step 3: Build debug APKs**

Run: `./gradlew :app:assembleDebug`

- [ ] **Step 4: Device smoke test**

Launch Emby playback, pause for at least 20 seconds, confirm repeated `/Sessions/Playing/Progress` logs with `IsPaused=true`, then stop near the credits and confirm the mark-played request succeeds.
