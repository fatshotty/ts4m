package net.fatshotty.tgstream.player.controls

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackKeyPolicyTest {
    @Test fun centerShowsControlsWhenHidden() {
        val action = PlaybackKeyPolicy.actionFor(
            keyCode = KeyEvent.KEYCODE_DPAD_CENTER,
            action = KeyEvent.ACTION_DOWN,
            controlsVisible = false,
            seekFocused = false,
        )

        assertEquals(PlaybackKeyAction.SHOW_CONTROLS, action)
    }

    @Test fun leftDoesNotTrapFocusWhenSeekBarFocusedButNotScrubbing() {
        val action = PlaybackKeyPolicy.actionFor(
            keyCode = KeyEvent.KEYCODE_DPAD_LEFT,
            action = KeyEvent.ACTION_DOWN,
            controlsVisible = true,
            seekFocused = true,
        )

        assertEquals(PlaybackKeyAction.IGNORE, action)
    }

    @Test fun rightDoesNotTrapFocusWhenSeekBarFocusedButNotScrubbing() {
        val action = PlaybackKeyPolicy.actionFor(
            keyCode = KeyEvent.KEYCODE_DPAD_RIGHT,
            action = KeyEvent.ACTION_DOWN,
            controlsVisible = true,
            seekFocused = true,
        )

        assertEquals(PlaybackKeyAction.IGNORE, action)
    }

    @Test fun leftScrubsWhenSeekBarFocusedAndScrubbing() {
        val action = PlaybackKeyPolicy.actionFor(
            keyCode = KeyEvent.KEYCODE_DPAD_LEFT,
            action = KeyEvent.ACTION_DOWN,
            controlsVisible = true,
            seekFocused = true,
            seekScrubbing = true,
        )

        assertEquals(PlaybackKeyAction.SCRUB_BACK, action)
    }

    @Test fun rightScrubsWhenSeekBarFocusedAndScrubbing() {
        val action = PlaybackKeyPolicy.actionFor(
            keyCode = KeyEvent.KEYCODE_DPAD_RIGHT,
            action = KeyEvent.ACTION_DOWN,
            controlsVisible = true,
            seekFocused = true,
            seekScrubbing = true,
        )

        assertEquals(PlaybackKeyAction.SCRUB_FORWARD, action)
    }

    @Test fun leftStartsScrubbingWhenControlsHidden() {
        val action = PlaybackKeyPolicy.actionFor(
            keyCode = KeyEvent.KEYCODE_DPAD_LEFT,
            action = KeyEvent.ACTION_DOWN,
            controlsVisible = false,
            seekFocused = false,
        )

        assertEquals(PlaybackKeyAction.SCRUB_BACK, action)
    }

    @Test fun rightStartsScrubbingWhenControlsHidden() {
        val action = PlaybackKeyPolicy.actionFor(
            keyCode = KeyEvent.KEYCODE_DPAD_RIGHT,
            action = KeyEvent.ACTION_DOWN,
            controlsVisible = false,
            seekFocused = false,
        )

        assertEquals(PlaybackKeyAction.SCRUB_FORWARD, action)
    }

    @Test fun mediaRewindStartsScrubbingWhenControlsHidden() {
        val action = PlaybackKeyPolicy.actionFor(
            keyCode = KeyEvent.KEYCODE_MEDIA_REWIND,
            action = KeyEvent.ACTION_DOWN,
            controlsVisible = false,
            seekFocused = false,
        )

        assertEquals(PlaybackKeyAction.SCRUB_BACK, action)
    }

    @Test fun mediaFastForwardStartsScrubbingWhenControlsHidden() {
        val action = PlaybackKeyPolicy.actionFor(
            keyCode = KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            action = KeyEvent.ACTION_DOWN,
            controlsVisible = false,
            seekFocused = false,
        )

        assertEquals(PlaybackKeyAction.SCRUB_FORWARD, action)
    }

    @Test fun leftDoesNotHijackFocusNavigationWhenControlsVisible() {
        val action = PlaybackKeyPolicy.actionFor(
            keyCode = KeyEvent.KEYCODE_DPAD_LEFT,
            action = KeyEvent.ACTION_DOWN,
            controlsVisible = true,
            seekFocused = false,
        )

        assertEquals(PlaybackKeyAction.IGNORE, action)
    }

    @Test fun rightDoesNotHijackFocusNavigationWhenControlsVisible() {
        val action = PlaybackKeyPolicy.actionFor(
            keyCode = KeyEvent.KEYCODE_DPAD_RIGHT,
            action = KeyEvent.ACTION_DOWN,
            controlsVisible = true,
            seekFocused = false,
        )

        assertEquals(PlaybackKeyAction.IGNORE, action)
    }

    @Test fun mediaRewindScrubsWhenControlsVisibleAndSeekBarNotFocused() {
        val action = PlaybackKeyPolicy.actionFor(
            keyCode = KeyEvent.KEYCODE_MEDIA_REWIND,
            action = KeyEvent.ACTION_DOWN,
            controlsVisible = true,
            seekFocused = false,
        )

        assertEquals(PlaybackKeyAction.SCRUB_BACK, action)
    }

    @Test fun mediaFastForwardScrubsWhenControlsVisibleAndSeekBarNotFocused() {
        val action = PlaybackKeyPolicy.actionFor(
            keyCode = KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            action = KeyEvent.ACTION_DOWN,
            controlsVisible = true,
            seekFocused = false,
        )

        assertEquals(PlaybackKeyAction.SCRUB_FORWARD, action)
    }

    @Test fun mediaPlayStartsPlayback() {
        val action = PlaybackKeyPolicy.actionFor(
            keyCode = KeyEvent.KEYCODE_MEDIA_PLAY,
            action = KeyEvent.ACTION_DOWN,
            controlsVisible = true,
            seekFocused = false,
        )

        assertEquals(PlaybackKeyAction.PLAY, action)
    }

    @Test fun mediaPausePausesPlayback() {
        val action = PlaybackKeyPolicy.actionFor(
            keyCode = KeyEvent.KEYCODE_MEDIA_PAUSE,
            action = KeyEvent.ACTION_DOWN,
            controlsVisible = true,
            seekFocused = false,
        )

        assertEquals(PlaybackKeyAction.PAUSE, action)
    }

    @Test fun infoKeysAreOptionalStatsShortcuts() {
        val action = PlaybackKeyPolicy.actionFor(
            keyCode = KeyEvent.KEYCODE_INFO,
            action = KeyEvent.ACTION_DOWN,
            controlsVisible = true,
            seekFocused = false,
        )

        assertEquals(PlaybackKeyAction.TOGGLE_STATS, action)
    }
}
