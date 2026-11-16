package org.tgstream.app.player.controls

import android.view.KeyEvent

class RemoteScrubSession {
    private var activeKeyCode: Int? = null

    val isActive: Boolean
        get() = activeKeyCode != null

    fun begin(keyCode: Int): Boolean {
        if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) {
            return false
        }
        activeKeyCode = keyCode
        return true
    }

    fun finishIfReleased(keyCode: Int, action: Int): Boolean {
        if (action != KeyEvent.ACTION_UP || activeKeyCode != keyCode) return false
        activeKeyCode = null
        return true
    }

    fun reset() {
        activeKeyCode = null
    }
}
