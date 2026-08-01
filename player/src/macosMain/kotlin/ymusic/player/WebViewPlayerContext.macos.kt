package ymusic.player

import platform.AppKit.NSView
import ymusic.ytmedia.proxy.LocalMediaProxy

/** macOS WebView player context: the [container] the player injects its WKWebView into, plus the
 *  optional player-cache [mediaProxy] (served over the WKWebView message channel — see
 *  `AppleProxyChannel`; its client must carry the `WKHTTPCookieStore` cookies + the WKWebView UA). */
actual class WebViewPlayerContext(val container: NSView, val mediaProxy: LocalMediaProxy? = null)
