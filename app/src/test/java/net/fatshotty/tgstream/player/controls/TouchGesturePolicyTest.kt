package net.fatshotty.tgstream.player.controls

import org.junit.Assert.assertEquals
import org.junit.Test

class TouchGesturePolicyTest {
    @Test fun singleTapTogglesPlayback() {
        assertEquals(TouchGestureAction.TOGGLE_PLAY, TouchGesturePolicy.singleTapAction())
    }

    @Test fun doubleTapOnLeftSideSeeksBack() {
        assertEquals(TouchGestureAction.SEEK_BACK, TouchGesturePolicy.doubleTapAction(width = 1000, x = 250f))
    }

    @Test fun doubleTapOnRightSideSeeksForward() {
        assertEquals(TouchGestureAction.SEEK_FORWARD, TouchGesturePolicy.doubleTapAction(width = 1000, x = 750f))
    }

    @Test fun doubleTapAtCenterSeeksForward() {
        assertEquals(TouchGestureAction.SEEK_FORWARD, TouchGesturePolicy.doubleTapAction(width = 1000, x = 500f))
    }
}
