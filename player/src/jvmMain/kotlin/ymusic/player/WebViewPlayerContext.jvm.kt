package ymusic.player

import org.cef.CefClient
import java.awt.Container

/**
 * JVM desktop WebView player context. The app owns JCEF's global `CefApp` lifecycle (init/download)
 * and hands the player a ready [cefClient] plus the AWT [container] the player adds its browser
 * component into (container-injection). See the playground for the `CefApp` bootstrap.
 */
actual class WebViewPlayerContext(
    val cefClient: CefClient,
    val container: Container,
)
