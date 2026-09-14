package io.ymusic.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Overlay chrome state shared by the main window (scroll, pause, auto-hide)
 * and the desktop chrome window (paint). Do not keep show/hide only in the
 * chrome composition — that window often skips parent updates.
 *
 * Native video NSView gets mouse after the chrome window is fully transparent
 * (macOS click-through). Do not copy those AWT events into Compose.
 */
object VideoOverlayNative {
    /** Portrait: inline transport has scrolled off. Landscape/fullscreen: always true. */
    var armed by mutableStateOf(false)

    /** User-facing chrome on the video. */
    var visible by mutableStateOf(true)

    /** Seek-bar drag holds the overlay until release. */
    var scrubbing by mutableStateOf(false)

    /** Hide the VT overlay so an in-window context menu can receive clicks over the video. */
    var coverHole by mutableStateOf(false)

    /** True while the player window is in live resize; same still as [coverHole]. */
    var liveResize by mutableStateOf(false)

    /** Bumped on every show so the auto-hide timer restarts. */
    var showGen by mutableIntStateOf(0)
        internal set

    val showing: Boolean
        get() = armed && visible

    /** Desktop chrome window raise; invoked on the EDT from [show]. */
    @Volatile
    var raiseWindow: (() -> Unit)? = null

    fun poke() {
        if (!armed) return
        if (visible) {
            visible = false
        } else {
            show()
        }
    }

    fun show() {
        val raise = armed && !visible
        visible = true
        showGen++
        if (raise) raiseWindow?.invoke()
    }
}
