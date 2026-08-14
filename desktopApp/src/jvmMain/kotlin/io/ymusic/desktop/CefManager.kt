package io.ymusic.desktop

import io.ymusic.build.DEBUG_BUILD
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
        installShutdownGuards()
        val root = ymusicDataDir()
        val cefApp = CefAppBuilder().apply {
            setInstallDir(File(root, "jcef-bundle"))
            setProgressHandler(ConsoleProgressHandler())
            // Chromium's desktop autoplay policy silently rejects unmuted play() without a user
            // gesture *inside the browser* — the app's own controls never count as one. Android's
            // WebView equivalent is mediaPlaybackRequiresUserGesture = false.
            addJcefArgs("--autoplay-policy=no-user-gesture-required")
            // Chromium's own media-key integration registers with the SAME process-global
            // MPRemoteCommandCenter as the app's DesktopMediaSession and takes it over on behalf
            // of the embedded page: the page handles play/pause (so toggle appears to work) but
            // declares no next/prev, which Chromium then disables — media keys half-work. The app
            // owns the OS media surface; Chromium must not touch it.
            addJcefArgs("--disable-features=HardwareMediaKeyHandling")
            // Google refuses sign-in from a browser it fingerprints as embedded/automated ("this
            // browser may not be secure"). Drop the AutomationControlled Blink flag (sets
            // navigator.webdriver=false, removes the automation signal) — the main tell beyond UA.
            addJcefArgs("--disable-blink-features=AutomationControlled")
            // DevTools: point a real Chrome at http://localhost:9222 to inspect ANY JCEF browser
            // (the login window AND the OSR player). Debug builds only — it exposes the browser
            // to local processes.
            if (DEBUG_BUILD) addJcefArgs("--remote-debugging-port=9222")
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

    /** A fresh client off the already-initialized CefApp (a [createClient] must have run first) —
     *  same process, same one Chromium profile; used by dev windows like the cookie inspector. */
    fun createExtraClient(): CefClient = checkNotNull(app) { "CEF not initialized" }.createClient()

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

    internal companion object {
        const val COOKIE_MANAGER_ATTEMPTS = 200
        const val COOKIE_MANAGER_POLL_MS = 25L
        const val CEF_SHUTDOWN_GRACE_MS = 5_000L
        const val SIGNAL_REASSERT_MS = 1_000L

        private var guardsInstalled = false

        /**
         * Keep a stop signal able to stop the process despite CEF. Two independent failure modes:
         *
         *  - Chromium REPLACES the process's SIGTERM/SIGINT handlers once its subsystems spin up
         *    (verified live: a browsing app swallows both; a freshly-booted one dies normally) —
         *    gradle's task cancellation delivers SIGTERM, so stopping the run task left the app
         *    alive. A daemon thread re-asserts the JVM's handlers every second (sigaction is
         *    microseconds); the handler funnels into the JVM's normal exit, same as a window close.
         *
         *  - JCEF 146's graceful teardown can wedge during JVM shutdown: jcefmaven's own shutdown
         *    hook calls CefApp.dispose(), whose final N_Shutdown is invokeLater'd onto an EDT that
         *    no longer pumps once shutdown began. Give CEF a grace window to reach TERMINATED,
         *    then halt the VM unconditionally.
         */
        @Synchronized
        fun installShutdownGuards() {
            if (guardsInstalled) return
            guardsInstalled = true
            Runtime.getRuntime().addShutdownHook(
                Thread {
                    val deadline = System.currentTimeMillis() + CEF_SHUTDOWN_GRACE_MS
                    while (CefApp.getState() != CefApp.CefAppState.TERMINATED &&
                        System.currentTimeMillis() < deadline
                    ) {
                        Thread.sleep(50)
                    }
                    Runtime.getRuntime().halt(0)
                },
            )
            val exit = sun.misc.SignalHandler { kotlin.system.exitProcess(0) }
            Thread {
                while (true) {
                    runCatching {
                        sun.misc.Signal.handle(sun.misc.Signal("TERM"), exit)
                        sun.misc.Signal.handle(sun.misc.Signal("INT"), exit)
                    }
                    Thread.sleep(SIGNAL_REASSERT_MS)
                }
            }.apply {
                isDaemon = true
                name = "cef-signal-reassert"
            }.start()
        }

        // A real desktop Chrome UA — must mirror engine-js session.ts DESKTOP_UA (one YouTube
        // identity) AND carry the bundled Chromium's real major version: Sec-CH-UA client hints
        // can't be suppressed and would expose a version mismatch. Bump alongside Versions.jcef.
        const val CHROME_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36"
    }
}
