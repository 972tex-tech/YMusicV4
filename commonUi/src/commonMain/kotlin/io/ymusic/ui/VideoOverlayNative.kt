package io.ymusic.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Overlay chrome state lives here so the main window (scroll, pause) and the
 * chrome Window (paint) share one snapshot. Do not keep show/hide in the chrome
 * composition — that window often skips parent updates.
 *
 * Native video NSView gets mouse after the chrome window fades to fully transparent
 * (macOS click-through). Do not copy AWT events into the chrome window — that froze
 * the player.
 */
object VideoOverlayNative {
    var playing by mutableStateOf(false)

    /** Portrait: inline transport has scrolled off. Landscape/fullscreen: always true. */
    var armed by mutableStateOf(false)

    /** User-facing chrome on the video (and/or after auto-hide). */
    var visible by mutableStateOf(true)

    var scrubbing by mutableStateOf(false)

    /** Bumped on every show so the auto-hide timer restarts. */
    var showGen by mutableIntStateOf(0)
        internal set

    val showing: Boolean
        get() = armed && visible

    /** Last overlay visibility (native click-through). */
    @Volatile
    var shown: Boolean = true

    /** Desktop chrome window raise; invoked on the EDT from [poke]. */
    @Volatile
    var raiseWindow: (() -> Unit)? = null

    fun poke(raise: Boolean) {
        overlayLog("poke raise=$raise armed=$armed vis=$visible")
        if (!raise || !armed) return
        if (visible) {
            visible = false
        } else {
            show()
        }
    }

    fun show() {
        val raise = armed && !visible
        overlayLog("show() armed=$armed vis=$visible -> vis=true raise=$raise gen=$showGen")
        visible = true
        showGen++
        shown = armed
        if (raise) raiseWindow?.invoke()
    }
}

internal fun overlayLog(msg: String) {
    println("[overlay] $msg")
}
