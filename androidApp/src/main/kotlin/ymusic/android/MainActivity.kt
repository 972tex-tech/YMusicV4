package ymusic.android

import android.content.ComponentCallbacks2
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
import kmphttp.KmpHttpClient
import kmphttp.create
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import ymusic.dynamicengine.DynamicEngineManager
import ymusic.dynamicengine.androidEngineCacheStore
import ymusic.imageloader.DiskScaledImageCache
import ymusic.imageloader.ImageLoader
import ymusic.imageloader.defaultMemoryCacheBudget
import ymusic.imageloader.fileFetcher
import ymusic.imageloader.networkFetcher
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
    private var imageLoader: ImageLoader? = null

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val loader = imageLoader ?: return
        when {
            // In the background LRU list — decoded thumbnails are pure regenerable weight, drop all.
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> loader.trimMemoryCache(0f)
            // Running but memory is tight (or UI just hidden) — keep the hot half.
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> loader.trimMemoryCache(0.5f)
        }
    }

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
        // One shared video host (a FrameLayout) both engines render into: the WebView engine adds its
        // WebView, the local engine adds its SurfaceView, each owning its own view's visibility.
        // Views must be born on the main thread; EVERYTHING ELSE below runs off it — the first
        // CookieManager.getInstance() alone initializes the whole WebView provider, which froze the
        // boot spinner for seconds on a slow device when it ran on main.
        val videoContainer = FrameLayout(this)
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            buildGraphOffMain(videoContainer)
        }
    }

    private suspend fun buildGraphOffMain(videoContainer: FrameLayout): AppGraph {
        // The cookie store is the WebView CookieManager — shared with the WebView YouTube player,
        // persistent across launches (as legacy YMusic did). The engine's OWN HTTP client carries
        // NO jar: the engine JS cooks every Cookie header itself against this store (cookie/get|set
        // protocol), so cookie policy lives in the remotely-updatable bundle.
        val platformCookies = WebViewCookieJar()
        val engineClient = KmpHttpClient.create { }
        val settings = PrefsSettingsStore.open(this)
        val engines = DynamicEngineManager(
            engineClient,
            appVersion = 1,
            logger = LogcatLogger(),
            cacheStore = androidEngineCacheStore(applicationContext),
            cookieJar = platformCookies,
            settings = settings,
        )
        engines.start()

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
                            // The embed fetch is a browser-identity request — it rides the platform
                            // cookie store like the WebView's own traffic (unlike the engine client).
                            httpClient = KmpHttpClient.create { cookieJar = platformCookies },
                        )
                    }
                },
            ),
        )

        // Thumbnails: everything is a fetcher — http on a cache-less client (originals stream to the
        // fetcher's disk cache, decode from file) + local files. Memory budget scales with the
        // device's heap class; onTrimMemory (above) sheds it under pressure.
        val imageLoader = ImageLoader(
            fetchers = listOf(
                networkFetcher(
                    KmpHttpClient.create { },
                    FileSystem.SYSTEM,
                    cacheDir.resolve("image-source-cache").toOkioPath(),
                ),
                fileFetcher(),
            ),
            scaledCache = DiskScaledImageCache(FileSystem.SYSTEM, cacheDir.resolve("image-scaled-cache").toOkioPath()),
            logger = LogcatLogger(),
            memoryCacheBytes = defaultMemoryCacheBudget(this),
        ).also { this.imageLoader = it }

        val versionName = packageManager.getPackageInfo(packageName, 0).versionName
        return AppGraph(
            engines,
            player,
            appVersion = listOfNotNull(versionName, android.os.Build.SUPPORTED_ABIS.firstOrNull()).joinToString(" · "),
            imageLoader = imageLoader,
            settings = settings,
        ) { modifier ->
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
