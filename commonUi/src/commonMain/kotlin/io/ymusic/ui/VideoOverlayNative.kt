package io.ymusic.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.concurrent.Volatile

/**
 * Overlay chrome on the video: armed when the transport is not inline (scrolled off /
 * landscape / fullscreen). Desktop paints this in the layout hole over the underlay Frame.
 */
object VideoOverlayNative {
    /** Portrait: inline transport has scrolled off. Landscape/fullscreen: always true. */
    var armed by mutableStateOf(false)

    /** User-facing chrome on the video. */
    var visible by mutableStateOf(true)

    /** Seek-bar drag holds the overlay until release. */
    var scrubbing by mutableStateOf(false)

    /** Bumped on every show so the auto-hide timer restarts. */
    var showGen by mutableIntStateOf(0)
        internal set

    val showing: Boolean
        get() = armed && visible

    /** Desktop: restack the video underlay behind the UI window. */
    @Volatile
    var onOverlayPress: (() -> Unit)? = null

    fun poke() {
        onOverlayPress?.invoke()
        if (!armed) return
        if (visible) {
            visible = false
        } else {
            show()
        }
    }

    fun show() {
        visible = true
        showGen++
    }
}
