package ymusic.playground

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.friwi.jcefmaven.CefAppBuilder
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter
import me.friwi.jcefmaven.impl.progress.ConsoleProgressHandler
import org.cef.CefApp
import org.cef.CefClient
import java.io.File

/**
 * Global JCEF (`CefApp`) bootstrap for the desktop playground — ported from the ymix-client
 * experiment. First run downloads the CEF native bundle (~150 MB) into a project-local dir. The app
 * owns this lifecycle; `:player` only drives the [CefClient] it is handed.
 */
class CefManager {
    private var app: CefApp? = null

    /** Build/initialize CefApp (blocking, off the UI thread) and return a fresh client. */
    suspend fun createClient(): CefClient = withContext(Dispatchers.IO) {
        val root = File(System.getProperty("user.home"), ".ymusic-playground")
        val cefApp = CefAppBuilder().apply {
            setInstallDir(File(root, "jcef-bundle"))
            setProgressHandler(ConsoleProgressHandler())
            setAppHandler(object : MavenCefAppHandlerAdapter() {
                override fun onBeforeTerminate(): Boolean = true
            })
            cefSettings.apply {
                root_cache_path = File(root, "jcef-cache").absolutePath
                cache_path = File(root, "jcef-cache").absolutePath
                persist_session_cookies = true
                windowless_rendering_enabled = false
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
