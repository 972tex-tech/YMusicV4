package ymusic.player

import android.content.Context
import android.view.ViewGroup
import ymusic.ytmedia.proxy.LocalMediaProxy

/**
 * Android WebView player context: the [Context] to build the WebView and the [container] the player
 * adds its WebView into (container-injection — the player fills the container, the caller owns it).
 *
 * [mediaProxy] is the optional player-cache router: when present the WebView intercepts the bundle's
 * `/ymusicForward` requests (`shouldInterceptRequest`) and serves them from it; when null there is no
 * interception. Its client must carry the WebView `CookieManager` cookies + the WebView UA
 * (see [LocalMediaProxy]).
 */
actual class WebViewPlayerContext(
    val context: Context,
    val container: ViewGroup,
    val mediaProxy: LocalMediaProxy? = null,
)
