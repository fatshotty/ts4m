package net.fatshotty.tgstream.player.controls

import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteScrubSessionTest {
    @Test fun dpadRightSessionStaysActiveUntilMatchingRelease() {
        val session = RemoteScrubSession()

        assertTrue(session.begin(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertTrue(session.isActive)
        assertFalse(session.finishIfReleased(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.ACTION_UP))
        assertTrue(session.isActive)
        assertTrue(session.finishIfReleased(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_UP))
        assertFalse(session.isActive)
    }

    @Test fun ignoresNonDpadScrubKeys() {
        val session = RemoteScrubSession()

        assertFalse(session.begin(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD))
        assertFalse(session.isActive)
    }

    @Test fun resetClearsActiveSession() {
        val session = RemoteScrubSession()

        session.begin(KeyEvent.KEYCODE_DPAD_LEFT)
        session.reset()

        assertFalse(session.isActive)
    }
}
