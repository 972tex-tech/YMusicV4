package io.ymusic.player

import io.ymusic.ytmedia.proxy.LocalMediaProxy
import platform.UIKit.UIView

/**
 * iOS WebView player context: the [container] the player injects its WKWebView into, plus the optional
 * player-cache [mediaProxy]. When present, webview-js serves `/videoplayback` over the WKWebView message
 * channel (WKWebView can't intercept https) — see `AppleProxyChannel`. Its client must carry the
 * `WKHTTPCookieStore` cookies + the WKWebView UA (see [LocalMediaProxy]).
 */
actual class WebViewPlayerContext(val container: UIView, val mediaProxy: LocalMediaProxy? = null)
