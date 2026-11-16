package org.tgstream.app.ui

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Adds the system-bar + display-cutout insets on top of the view's current
 * padding. Needed because targetSdk 35+ forces edge-to-edge, so without this the
 * content (and the Log buttons) draw under the status / navigation bars.
 */
fun View.applySystemBarInsets() {
    val baseL = paddingLeft
    val baseT = paddingTop
    val baseR = paddingRight
    val baseB = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
        )
        v.updatePadding(
            left = baseL + bars.left,
            top = baseT + bars.top,
            right = baseR + bars.right,
            bottom = baseB + bars.bottom,
        )
        insets
    }
    ViewCompat.requestApplyInsets(this)
}
