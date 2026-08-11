package io.ymusic.playground

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.ymusic.player.LocalPlayerContext
import io.ymusic.player.createLocalPlayer
import kmphttp.KmpHttpClient
import kmphttp.create
import org.cef.CefClient
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * Desktop (JVM) playground for :player. Two hand-check hosts, switchable at the top:
 *  - **Local** — a local file through the VLCJ backend (needs libVLC).
 *  - **YouTube** — a video through the WebView engine on JCEF (needs the built `webview-js` bundle;
 *    first run downloads the CEF native bundle).
 */
fun main() = application {
    val cefManager = remember { CefManager() }
    var mode by remember { mutableStateOf(Mode.YOUTUBE) }
    Window(
        onCloseRequest = {
            cefManager.dispose()
            exitApplication()
        },
        title = "YMusic · player playground",
        state = rememberWindowState(size = DpSize(560.dp, 680.dp)),
    ) {
        MaterialTheme {
            Surface {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(onClick = { mode = Mode.LOCAL }) { Text("Local") }
                        Button(onClick = { mode = Mode.YOUTUBE }) { Text("YouTube") }
                    }
                    when (mode) {
                        Mode.LOCAL -> LocalHost()
                        Mode.YOUTUBE -> YoutubeHost(cefManager)
                    }
                }
            }
        }
    }
}

private enum class Mode { LOCAL, YOUTUBE }

@Composable
private fun LocalHost() {
    val player = remember { createLocalPlayer(LocalPlayerContext()) }
    DisposableEffect(Unit) { onDispose { player.release() } }
    PlayerScreen(player = player, filePicker = { pickMediaFile() })
}

@Composable
private fun YoutubeHost(cefManager: CefManager) {
    val httpClient = remember { KmpHttpClient.create {} }
    var client by remember { mutableStateOf<CefClient?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val engineJs = remember { runCatching { readEngineJs() }.getOrElse { error = it.message; null } }

    LaunchedEffect(Unit) {
        if (error == null) runCatching { client = cefManager.createClient() }.onFailure { error = it.message }
    }

    when {
        error != null -> Centered { Text("WebView unavailable: $error", color = MaterialTheme.colorScheme.error) }
        client == null || engineJs == null -> Centered {
            CircularProgressIndicator()
            Text("Starting Chromium… (first run downloads the CEF bundle)")
        }
        else -> WebViewPlayerScreen(cefClient = client!!, engineJs = engineJs, httpClient = httpClient)
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

/** Read the built webview-js bundle (run `npm run build` in webview-js/ first). */
private fun readEngineJs(): String {
    val candidates = listOf(
        "webview-js/dist/webview_ymusic.js",
        "../webview-js/dist/webview_ymusic.js",
    )
    candidates.map(::File).firstOrNull { it.exists() }?.let { return it.readText() }
    error("webview_ymusic.js not found (cwd=${System.getProperty("user.dir")}); run `npm run build` in webview-js/")
}

/** Native AWT open dialog — blocking/modal, runs on the Compose main (event) thread. */
private fun pickMediaFile(): String? {
    val dialog = FileDialog(null as Frame?, "Choose a media file", FileDialog.LOAD)
    dialog.isVisible = true
    val dir = dialog.directory ?: return null
    val file = dialog.file ?: return null
    return dir + file
}
