package ymusic.player

import android.content.Context
import android.view.ViewGroup

/**
 * Android WebView player context: the [Context] to build the WebView and the [container] the player
 * adds its WebView into (container-injection — the player fills the container, the caller owns it).
 */
actual class WebViewPlayerContext(
    val context: Context,
    val container: ViewGroup,
)
