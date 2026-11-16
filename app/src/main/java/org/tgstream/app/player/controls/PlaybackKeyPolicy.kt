package org.tgstream.app.player.controls

import android.view.KeyEvent

enum class PlaybackKeyAction {
    IGNORE,
    SHOW_CONTROLS,
    PLAY,
    PAUSE,
    TOGGLE_PLAY,
    SEEK_BACK,
    SEEK_FORWARD,
    SCRUB_BACK,
    SCRUB_FORWARD,
    TOGGLE_STATS,
    HIDE_CONTROLS,
}

object PlaybackKeyPolicy {
    fun actionFor(
        keyCode: Int,
        action: Int,
        controlsVisible: Boolean,
        seekFocused: Boolean,
        seekScrubbing: Boolean = false,
    ): PlaybackKeyAction {
        if (action != KeyEvent.ACTION_DOWN) return PlaybackKeyAction.IGNORE
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> if (controlsVisible) PlaybackKeyAction.IGNORE else PlaybackKeyAction.SHOW_CONTROLS
            KeyEvent.KEYCODE_MEDIA_PLAY -> PlaybackKeyAction.PLAY
            KeyEvent.KEYCODE_MEDIA_PAUSE -> PlaybackKeyAction.PAUSE
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> PlaybackKeyAction.TOGGLE_PLAY
            KeyEvent.KEYCODE_DPAD_LEFT -> if (controlsVisible && seekFocused && seekScrubbing) {
                PlaybackKeyAction.SCRUB_BACK
            } else if (!controlsVisible) {
                PlaybackKeyAction.SCRUB_BACK
            } else {
                PlaybackKeyAction.IGNORE
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> PlaybackKeyAction.SCRUB_BACK
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (controlsVisible && seekFocused && seekScrubbing) {
                PlaybackKeyAction.SCRUB_FORWARD
            } else if (!controlsVisible) {
                PlaybackKeyAction.SCRUB_FORWARD
            } else {
                PlaybackKeyAction.IGNORE
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> PlaybackKeyAction.SCRUB_FORWARD
            KeyEvent.KEYCODE_DPAD_UP -> if (controlsVisible) PlaybackKeyAction.IGNORE else PlaybackKeyAction.SHOW_CONTROLS
            KeyEvent.KEYCODE_BACK -> if (controlsVisible) PlaybackKeyAction.HIDE_CONTROLS else PlaybackKeyAction.IGNORE
            KeyEvent.KEYCODE_INFO,
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_PROG_YELLOW -> PlaybackKeyAction.TOGGLE_STATS
            else -> PlaybackKeyAction.IGNORE
        }
    }
}
