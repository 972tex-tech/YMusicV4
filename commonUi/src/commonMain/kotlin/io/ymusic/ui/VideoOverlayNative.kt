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
fun overlayLog(msg: String) {
    println("[overlay] $msg")
}

object VideoOverlayNative {
    private val armedState = mutableStateOf(false)
    private val visibleState = mutableStateOf(true)
    private val coverHoleState = mutableStateOf(false)

    /** Portrait: inline transport has scrolled off. Landscape/fullscreen: always true. */
    var armed: Boolean
        get() = armedState.value
        set(value) {
            if (armedState.value == value) return
            overlayLog("armed ${armedState.value} -> $value")
            armedState.value = value
        }

    /** User-facing chrome on the video. */
    var visible: Boolean
        get() = visibleState.value
        set(value) {
            if (visibleState.value == value) return
            overlayLog("visible ${visibleState.value} -> $value")
            visibleState.value = value
        }

    /** Seek-bar drag holds the overlay until release. */
    var scrubbing by mutableStateOf(false)

    /** Hide the VT overlay so an in-window context menu can receive clicks over the video. */
    var coverHole: Boolean
        get() = coverHoleState.value
        set(value) {
            if (coverHoleState.value == value) return
            overlayLog("coverHole ${coverHoleState.value} -> $value")
            coverHoleState.value = value
        }

    /** Bumped on every show so the auto-hide timer restarts. */
    var showGen by mutableIntStateOf(0)
        internal set

    /** Quality/⋮/sleep menu is open — do not auto-hide or poke-toggle the overlay. */
    var menuHold by mutableStateOf(false)

    val showing: Boolean
        get() = armed && visible

    /** Desktop chrome window raise; invoked on the EDT from [show]. */
    @Volatile
    var raiseWindow: (() -> Unit)? = null

    fun poke() {
        overlayLog("poke armed=$armed visible=$visible menuHold=$menuHold")
        if (!armed || menuHold) return
        if (visible) {
            visible = false
        } else {
            show()
        }
    }

    fun show() {
        overlayLog("show armed=$armed visible=$visible")
        val raise = armed && !visible
        visible = true
        showGen++
        if (raise) {
            overlayLog("raiseWindow")
            raiseWindow?.invoke()
        }
    }
}
