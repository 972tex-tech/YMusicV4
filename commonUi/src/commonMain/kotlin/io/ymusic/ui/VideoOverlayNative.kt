package io.ymusic.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Native video NSView gets mouse after the chrome window fades to fully transparent
 * (macOS click-through). Do not copy AWT events into the chrome window — that froze
 * the player. A click [poke] toggles [AutoHidingOverlay]; hover does not.
 */
object VideoOverlayNative {
    var pokes by mutableIntStateOf(0)
        internal set

    /** Queue-scroll / play-pause: chrome Window reads this, not a parent lambda. */
    var reveals by mutableIntStateOf(0)
        internal set

    var playing by mutableStateOf(false)

    /** Portrait: inline transport has scrolled off. Landscape/fullscreen: always true. */
    var armed by mutableStateOf(true)

    /** Last overlay visibility. */
    @Volatile
    var shown: Boolean = true

    /** Desktop chrome window raise; invoked on the EDT from [poke]. */
    @Volatile
    var raiseWindow: (() -> Unit)? = null

    fun poke(raise: Boolean) {
        if (!raise) return
        pokes++
        /* toFront while the VT overlay NSView is handling the click freezes
           AppKit (AWT EDT is main). Raise only when the chrome was hidden. */
        if (!shown) raiseWindow?.invoke()
    }

    fun reveal() {
        reveals++
    }
}

/** Seek-bar scrub in the video overlay: true while the thumb is down. */
internal val LocalOverlayScrub = compositionLocalOf<(Boolean) -> Unit> { {} }
