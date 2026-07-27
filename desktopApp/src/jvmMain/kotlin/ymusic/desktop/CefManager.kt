package ymusic.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import me.friwi.jcefmaven.CefAppBuilder
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter
import me.friwi.jcefmaven.impl.progress.ConsoleProgressHandler
import org.cef.CefApp
import org.cef.CefClient
import org.cef.network.CefCookieManager
import java.io.File

/**
 * Global JCEF (`CefApp`) bootstrap. First run downloads the CEF native bundle (~150 MB) into
 * `~/.ymusic`. The shell owns this lifecycle; `:player` only drives the [CefClient] it is handed.
 */
class CefManager {
    private var app: CefApp? = null

    /** Build/initialize CefApp (blocking, off the UI thread) and return a fresh client.
     *  [cacheDir] overrides Chromium's profile (user-data) dir — Chromium holds a singleton lock
     *  per profile, so a second process on the same dir (a probe next to the running app) never
     *  creates its browser; probes must pass their own scratch profile. */
    suspend fun createClient(cacheDir: File? = null): CefClient = withContext(Dispatchers.IO) {
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
                val profile = cacheDir ?: File(root, "jcef-cache")
                root_cache_path = profile.absolutePath
                cache_path = profile.absolutePath
                persist_session_cookies = true
                // The player's browser renders off-screen (ImageOsrBrowser) into a lightweight
                // component — see :player WebViewBridge.jvm.kt.
                windowless_rendering_enabled = true
            }
        }.build()
        app = cefApp
        cefApp.createClient()
    }

    /** Wait until CEF publishes its global request context and persistent cookie store. */
    suspend fun awaitCookieManager(): CefCookieManager {
        repeat(COOKIE_MANAGER_ATTEMPTS) {
            val manager: CefCookieManager? = CefCookieManager.getGlobalManager()
            if (manager != null) return manager
            delay(COOKIE_MANAGER_POLL_MS)
        }
        error("E20041 CEF cookie manager did not initialize")
    }

    fun dispose() {
        app?.dispose()
        app = null
    }

    private companion object {
        const val COOKIE_MANAGER_ATTEMPTS = 200
        const val COOKIE_MANAGER_POLL_MS = 25L
    }
}
