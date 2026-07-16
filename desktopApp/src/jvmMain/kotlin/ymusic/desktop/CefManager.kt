package ymusic.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.friwi.jcefmaven.CefAppBuilder
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter
import me.friwi.jcefmaven.impl.progress.ConsoleProgressHandler
import org.cef.CefApp
import org.cef.CefClient
import java.io.File

/**
 * Global JCEF (`CefApp`) bootstrap. First run downloads the CEF native bundle (~150 MB) into
 * `~/.ymusic`. The shell owns this lifecycle; `:player` only drives the [CefClient] it is handed.
 */
class CefManager {
    private var app: CefApp? = null

    /** Build/initialize CefApp (blocking, off the UI thread) and return a fresh client. */
    suspend fun createClient(): CefClient = withContext(Dispatchers.IO) {
        val root = File(System.getProperty("user.home"), ".ymusic")
        val cefApp = CefAppBuilder().apply {
            setInstallDir(File(root, "jcef-bundle"))
            setProgressHandler(ConsoleProgressHandler())
            // Chromium's desktop autoplay policy silently rejects unmuted play() without a user
            // gesture *inside the browser* — the app's own controls never count as one. Android's
            // WebView equivalent is mediaPlaybackRequiresUserGesture = false.
            addJcefArgs("--autoplay-policy=no-user-gesture-required")
            setAppHandler(object : MavenCefAppHandlerAdapter() {
                override fun onBeforeTerminate(): Boolean = true
            })
            cefSettings.apply {
                root_cache_path = File(root, "jcef-cache").absolutePath
                cache_path = File(root, "jcef-cache").absolutePath
                persist_session_cookies = true
                // The player's browser renders off-screen (ImageOsrBrowser) into a lightweight
                // component — see :player WebViewBridge.jvm.kt.
                windowless_rendering_enabled = true
            }
        }.build()
        app = cefApp
        cefApp.createClient()
    }

    fun dispose() {
        app?.dispose()
        app = null
    }
}
