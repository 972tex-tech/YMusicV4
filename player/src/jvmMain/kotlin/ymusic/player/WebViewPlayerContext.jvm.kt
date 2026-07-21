package ymusic.player

import org.cef.CefClient
import org.cef.browser.JcefOsrSurface

/**
 * JVM desktop WebView player context. The app owns JCEF's global `CefApp` lifecycle (init/download)
 * and hands the player a ready [cefClient] plus the Compose-facing OSR [surface].
 */
actual class WebViewPlayerContext(
    val cefClient: CefClient,
    val surface: JcefOsrSurface,
)
