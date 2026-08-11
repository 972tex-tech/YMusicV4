package io.ymusic.player

import io.ymusic.ytmedia.proxy.LocalMediaProxy
import org.cef.CefClient
import org.cef.browser.JcefOsrSurface

/**
 * JVM desktop WebView player context. The app owns JCEF's global `CefApp` lifecycle (init/download)
 * and hands the player a ready [cefClient] plus the Compose-facing OSR [surface].
 *
 * [mediaProxy] is the optional player-cache router: when present the JCEF bridge intercepts the
 * bundle's `/ymusicForward` requests (`CefResourceHandler`) and serves them from it; when null there
 * is no interception and the bundle leaves `/videoplayback` alone. Its client must carry the CEF
 * cookie store + the WebView UA (see [LocalMediaProxy]).
 */
actual class WebViewPlayerContext(
    val cefClient: CefClient,
    val surface: JcefOsrSurface,
    val mediaProxy: LocalMediaProxy? = null,
)
