package org.tgstream.app.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackCompletionTest {
    @Test fun marksCompletedPlaybackAsWatched() {
        assertTrue(PlaybackCompletion.shouldMarkWatched(positionMs = 1, durationMs = 0, completed = true))
    }

    @Test fun marksNearEndPlaybackAsWatched() {
        assertTrue(PlaybackCompletion.shouldMarkWatched(positionMs = 90_000, durationMs = 100_000, completed = false))
    }

    @Test fun doesNotMarkMidPlaybackAsWatched() {
        assertFalse(PlaybackCompletion.shouldMarkWatched(positionMs = 89_999, durationMs = 100_000, completed = false))
    }

    @Test fun doesNotMarkWatchedWithoutKnownDuration() {
        assertFalse(PlaybackCompletion.shouldMarkWatched(positionMs = 90_000, durationMs = 0, completed = false))
    }
}
