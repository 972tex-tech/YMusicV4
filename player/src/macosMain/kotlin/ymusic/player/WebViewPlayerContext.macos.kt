package ymusic.player

import platform.AppKit.NSView

/** macOS WebView player context: the [container] the player injects its WKWebView into. */
actual class WebViewPlayerContext(val container: NSView)
