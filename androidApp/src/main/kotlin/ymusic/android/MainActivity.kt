package ymusic.android

import android.os.Bundle
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kmphttp.KmpHttpClient
import kmphttp.create
import ymusic.dynamicengine.DynamicEngineManager
import ymusic.player.LocalPlayerContext
import ymusic.player.PlayerEngines
import ymusic.player.WebViewPlayerContext
import ymusic.player.createLocalPlayer
import ymusic.player.createPlayer
import ymusic.player.createWebViewPlayer
import ymusic.ui.App
import ymusic.ui.AppGraph

/**
 * The Android shell: assembles the [AppGraph] (ExoPlayer local + WebView YouTube engines, the
 * scraping engine manager) and boots the shared :commonUi [App]. Background playback (the
 * MediaSessionService) lands with the system-integration milestone.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var graph by remember { mutableStateOf<AppGraph?>(null) }
            var error by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(Unit) {
                try {
                    graph = buildGraph()
                } catch (t: Throwable) {
                    error = t.message ?: t.toString()
                }
            }
            when {
                error != null -> Booting { Text("Startup failed: $error", color = MaterialTheme.colorScheme.error) }
                graph == null -> Booting { CircularProgressIndicator() }
                else -> App(graph!!)
            }
        }
    }

    private suspend fun buildGraph(): AppGraph {
        val httpClient = KmpHttpClient.create {}
        val engines = DynamicEngineManager(httpClient, appVersion = 1)
        engines.start()

        // One shared video host (a FrameLayout) both engines render into: the WebView engine adds its
        // WebView, the local engine adds its SurfaceView, each owning its own view's visibility.
        val videoContainer = FrameLayout(this)
        // The injected YouTube player script, bundled as an asset (copied from webview-js/dist).
        // Without it the WebView engine is absent and a YouTube item surfaces E60050.
        val webViewJs = runCatching {
            assets.open("webview_ymusic.js").readBytes().decodeToString()
        }.getOrNull()

        val player = createPlayer(
            PlayerEngines(
                local = { createLocalPlayer(LocalPlayerContext(this, videoContainer)) },
                webView = webViewJs?.let { js ->
                    {
                        createWebViewPlayer(
                            WebViewPlayerContext(this, videoContainer),
                            engineJs = js,
                            appVersion = 1,
                            httpClient = httpClient,
                        )
                    }
                },
            ),
        )

        return AppGraph(engines, player) { modifier ->
            AndroidView(factory = { videoContainer }, modifier = modifier)
        }
    }
}

@androidx.compose.runtime.Composable
private fun Booting(content: @androidx.compose.runtime.Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
