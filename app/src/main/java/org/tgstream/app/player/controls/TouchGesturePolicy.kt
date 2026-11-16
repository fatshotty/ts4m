package org.tgstream.app.player.controls

enum class TouchGestureAction {
    TOGGLE_PLAY,
    SEEK_BACK,
    SEEK_FORWARD,
}

object TouchGesturePolicy {
    fun singleTapAction(): TouchGestureAction = TouchGestureAction.TOGGLE_PLAY

    fun doubleTapAction(width: Int, x: Float): TouchGestureAction {
        return if (x < width / 2f) TouchGestureAction.SEEK_BACK else TouchGestureAction.SEEK_FORWARD
    }
}
