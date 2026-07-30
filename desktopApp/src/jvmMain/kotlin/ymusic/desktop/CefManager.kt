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
            // Google refuses sign-in from a browser it fingerprints as embedded/automated ("this
            // browser may not be secure"). Drop the AutomationControlled Blink flag (sets
            // navigator.webdriver=false, removes the automation signal) — the main tell beyond UA.
            // WebAuth: hide the WebAuthn API (window.PublicKeyCredential) so accounts.google.com
            // never offers passkey sign-in — JCEF has no platform authenticator, so the passkey
            // prompt is a dead end; absent the API, Google falls back to password.
            addJcefArgs("--disable-blink-features=AutomationControlled,WebAuth")
            // DevTools: point a real Chrome at http://localhost:9222 to inspect ANY JCEF browser
            // (the login window AND the OSR player). Dev-only — it exposes the browser to local
            // processes; drop before shipping.
            addJcefArgs("--remote-debugging-port=9222")
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
                // Google refuses sign-in in an "unsafe" browser and JCEF's default UA gives it away.
                // Present a real desktop Chrome UA (both navigator.userAgent and request headers) so
                // the accounts.google.com flow proceeds. Kept in step with the engine's DESKTOP_UA
                // (engine-js session.ts) so the player's embed browser and InnerTube also present one
                // identity to YouTube — bump both together.
                user_agent = CHROME_USER_AGENT
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

        // A real desktop Chrome UA — must mirror engine-js session.ts DESKTOP_UA (one YouTube
        // identity) AND carry the bundled Chromium's real major version: Sec-CH-UA client hints
        // can't be suppressed and would expose a version mismatch. Bump alongside Versions.jcef.
        const val CHROME_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36"
    }
}
