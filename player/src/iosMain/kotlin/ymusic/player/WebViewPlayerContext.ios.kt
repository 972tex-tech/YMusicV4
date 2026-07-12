package ymusic.player

import platform.UIKit.UIView

/** iOS WebView player context: the [container] the player injects its WKWebView into. */
actual class WebViewPlayerContext(val container: UIView)
