package io.ymusic.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal data class OverlayMenuItem(
    val label: String,
    val selected: Boolean = false,
    val onClick: () -> Unit,
)

internal data class OverlayMenu(
    val title: String,
    val items: List<OverlayMenuItem>,
)

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

    /** Top-bar picker hosted in the video chrome (above JAWT). */
    internal var menu by mutableStateOf<OverlayMenu?>(null)

    /** Bumped on every show so the auto-hide timer restarts. */
    var showGen by mutableIntStateOf(0)
        internal set

    val showing: Boolean
        get() = armed && visible

    /** Desktop chrome window raise; invoked on the EDT from [show]. */
    @Volatile
    var raiseWindow: (() -> Unit)? = null

    fun poke() {
        if (menu != null) {
            menu = null
            return
        }
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

    internal fun openMenu(menu: OverlayMenu) {
        this.menu = menu
        raiseWindow?.invoke()
    }
}
