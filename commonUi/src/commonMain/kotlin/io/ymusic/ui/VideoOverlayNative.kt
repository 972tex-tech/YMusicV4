package io.ymusic.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * Native video NSView gets mouse after the chrome window fades to fully transparent
 * (macOS click-through). Do not copy AWT events into the chrome window — that froze
 * the player. Bump [pokes] so [AutoHidingOverlay] can show again.
 */
object VideoOverlayNative {
    var pokes by mutableIntStateOf(0)
        internal set

    /** Last overlay visibility; hover pokes are ignored while already shown. */
    @Volatile
    var shown: Boolean = true

    /** Desktop chrome window raise; invoked on the EDT from [poke]. */
    @Volatile
    var raiseWindow: (() -> Unit)? = null

    fun poke(raise: Boolean) {
        if (shown && !raise) return
        pokes++
        if (raise) raiseWindow?.invoke()
    }
}
