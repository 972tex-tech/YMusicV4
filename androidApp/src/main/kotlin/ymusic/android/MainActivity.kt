package ymusic.android

import android.os.Bundle
import android.view.ViewGroup
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
import kmphttp.InMemoryCookieJar
import kmphttp.KmpHttpClient
import kmphttp.create
import ymusic.dynamicengine.DynamicEngineManager
import ymusic.dynamicengine.androidEngineCacheStore
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
        // A live cookie jar (NOT NO_COOKIES) so the scraping engine carries YouTube's anonymous
        // session cookies (VISITOR_INFO1_LIVE / YSC / consent) across its requests — the engine's
        // Cookies protocol and its own HTTP share this one jar.
        val httpClient = KmpHttpClient.create { cookieJar = InMemoryCookieJar() }
        val engines = DynamicEngineManager(
            httpClient,
            appVersion = 1,
            logger = LogcatLogger(),
            cacheStore = androidEngineCacheStore(applicationContext),
        )
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
            // The ONE shared videoContainer is hosted at two call sites (the docked preview and Now
            // Playing). They're logically exclusive, but across a transition frame Compose builds the
            // new host before disposing the old one — and Android's addView does NOT auto-detach
            // (unlike Swing/UIKit), so adding the container directly throws "child already has a
            // parent". So each AndroidView gets its own empty host and re-parents the shared container
            // into whichever is active, detaching from the previous parent first.
            AndroidView(
                factory = { context -> FrameLayout(context) },
                modifier = modifier,
                update = { host ->
                    (videoContainer.parent as? ViewGroup)?.removeView(videoContainer)
                    host.addView(videoContainer)
                },
            )
        }
    }
}

@androidx.compose.runtime.Composable
private fun Booting(content: @androidx.compose.runtime.Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
