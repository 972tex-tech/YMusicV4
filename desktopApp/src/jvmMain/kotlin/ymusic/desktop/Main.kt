package ymusic.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kmphttp.KmpHttpClient
import kmphttp.create
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ymusic.dynamicengine.DynamicEngineManager
import ymusic.player.LocalPlayer
import ymusic.player.LocalPlayerContext
import ymusic.player.PlayerEngines
import ymusic.player.WebViewPlayer
import ymusic.player.WebViewPlayerContext
import ymusic.player.awtVideoComponent
import ymusic.player.createLocalPlayer
import ymusic.player.createPlayer
import ymusic.player.createWebViewPlayer
import ymusic.ui.App
import ymusic.ui.AppGraph
import java.awt.BorderLayout
import java.io.File
import javax.swing.JPanel

/**
 * The desktop shell: boots JCEF + the scraping engine, assembles the [AppGraph] (player facade over
 * VLCJ local + JCEF WebView engines, the shared WebView container as the video surface), and runs
 * the shared [App].
 */
fun main() = application {
    val cefManager = remember { CefManager() }
    Window(
        onCloseRequest = {
            cefManager.dispose()
            exitApplication()
        },
        title = "YMusic",
        state = rememberWindowState(size = DpSize(480.dp, 860.dp)),
    ) {
        var graph by remember { mutableStateOf<AppGraph?>(null) }
        var error by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(Unit) {
            try {
                graph = buildGraph(cefManager)
            } catch (t: Throwable) {
                error = t.message ?: t.toString()
            }
        }

        when {
            error != null -> Booting { Text("Startup failed: $error", color = MaterialTheme.colorScheme.error) }
            graph == null -> Booting {
                CircularProgressIndicator()
                Text("Starting… (first run downloads the Chromium bundle)")
            }
            else -> App(graph!!)
        }
    }
}

private suspend fun buildGraph(cefManager: CefManager): AppGraph {
    val httpClient = KmpHttpClient.create {}
    val engines = DynamicEngineManager(httpClient, appVersion = 1)
    engines.start()

    val cefClient = cefManager.createClient()
    val webViewContainer = JPanel(BorderLayout())
    val webViewJs = withContext(Dispatchers.IO) { readWebViewJs() }

    val player = withContext(Dispatchers.Main) {
        createPlayer(
            PlayerEngines(
                local = { createLocalPlayer(LocalPlayerContext()) },
                webView = {
                    createWebViewPlayer(
                        WebViewPlayerContext(cefClient, webViewContainer),
                        engineJs = webViewJs,
                        appVersion = 1,
                        httpClient = httpClient,
                    )
                },
            ),
        )
    }

    return AppGraph(engines, player) { engine, modifier ->
        // The surface follows the engine kind: VLCJ's AWT component for local files, the shared
        // JCEF container (the player injects its browser into it) for YouTube.
        when (engine) {
            is WebViewPlayer -> SwingPanel(factory = { webViewContainer }, modifier = modifier)
            is LocalPlayer -> {
                val video = engine.awtVideoComponent()
                if (video != null) SwingPanel(factory = { video }, modifier = modifier)
            }
            else -> {}
        }
    }
}

/** The built webview-js bundle — the injected YouTube player script (run `npm run build` there). */
private fun readWebViewJs(): String {
    val candidates = listOf("webview-js/dist/webview_ymusic.js", "../webview-js/dist/webview_ymusic.js")
    candidates.map(::File).firstOrNull { it.exists() }?.let { return it.readText() }
    error("webview_ymusic.js not found (cwd=${System.getProperty("user.dir")}); run `npm run build` in webview-js/")
}

@Composable
private fun Booting(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}
