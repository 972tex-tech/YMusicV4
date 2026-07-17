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
import kmpcommon.uri.UriBuilder
import kmpffmpeg.metadata.FfmpegMetadataGetter
import kmphttp.DefaultPlatformCache
import kmphttp.KmpHttpClient
import kmphttp.create
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
import org.jetbrains.compose.resources.painterResource
import ymusic.dynamicengine.DynamicEngineManager
import ymusic.dynamicengine.desktopEngineCacheStore
import ymusic.imageloader.DiskScaledImageCache
import ymusic.imageloader.ImageLoader
import ymusic.imageloader.defaultMemoryCacheBudget
import ymusic.mediascanner.FfmpegMetadataExtractor
import ymusic.mediascanner.FileSystemDocumentsProvider
import ymusic.mediascanner.MediaScanner
import ymusic.mediastore.BetterMediaStore
import ymusic.mediastore.mediaStoreJdbcDriver
import ymusic.player.LocalPlayerContext
import ymusic.player.PlayerEngines
import ymusic.player.WebViewPlayerContext
import ymusic.player.createLocalPlayer
import ymusic.player.createPlayer
import ymusic.player.createWebViewPlayer
import ymusic.settings.FileSettingsStore
import ymusic.ui.App
import ymusic.ui.AppGraph
import ymusic.ui.resources.Res
import ymusic.ui.resources.ymusic_logo
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JPanel
import javax.swing.OverlayLayout

/**
 * The desktop shell: boots JCEF + the scraping engine, assembles the [AppGraph] (player facade over
 * VLCJ local + JCEF WebView engines, the shared WebView container as the video surface), and runs
 * the shared [App].
 */
fun main() {
    // Let Compose blend Swing interop content into its own layers: the OSR WebView and the VLCJ
    // callback component are both lightweight (Java2D-painted), so with blending they stop
    // painting over the drawer/popups. Must be set before the first ComposeWindow.
    System.setProperty("compose.interop.blending", "true")
    application {
        val cefManager = remember { CefManager() }
        Window(
            onCloseRequest = {
                cefManager.dispose()
                exitApplication()
            },
            title = "YMusic",
            icon = painterResource(Res.drawable.ymusic_logo),
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
}

private suspend fun buildGraph(cefManager: CefManager): AppGraph {
    val dataDir = File(System.getProperty("user.home"), ".ymusic").apply { mkdirs() }
    // Init CEF first: the cookie store is JCEF's global one (needs CefApp initialized before the
    // first access), shared with the WebView player and persisted on disk. The engine's OWN HTTP
    // client carries NO jar — the engine JS cooks every Cookie header itself against this store
    // (cookie/get|set protocol) so cookie policy lives in the remotely-updatable bundle.
    val cefClient = cefManager.createClient()
    val platformCookies = JcefCookieJar()
    val engineClient = KmpHttpClient.create { }
    val engines = withContext(Dispatchers.IO) {
        DynamicEngineManager(
            engineClient,
            appVersion = 1,
            logger = StdoutLogger,
            cacheStore = desktopEngineCacheStore(File(dataDir, "engine-cache")),
            cookieJar = platformCookies,
        )
    }
    engines.start()

    val mediaStore = withContext(Dispatchers.IO) {
        BetterMediaStore.create(mediaStoreJdbcDriver(File(dataDir, "media-store.db").absolutePath))
    }

    val settings = FileSettingsStore.open(File(dataDir, "settings.properties").absolutePath.toPath())

    // Thumbnails: own client with an HTTP disk cache (no cookies — public CDN images), plus the
    // scaled-image cache so warm starts skip the full-size decode.
    val imageLoader = ImageLoader(
        client = KmpHttpClient.create {
            cache = DefaultPlatformCache(File(dataDir, "image-http-cache").absolutePath.toPath(), 128L * 1024 * 1024)
        },
        scaledCache = DiskScaledImageCache(FileSystem.SYSTEM, File(dataDir, "image-scaled-cache").absolutePath.toPath()),
        logger = StdoutLogger,
        memoryCacheBytes = defaultMemoryCacheBudget(),
    )

    // One shared video host both engines render into (OverlayLayout = the Swing FrameLayout analog):
    // the WebView adds its browser, the VLCJ engine adds its video component, each toggling its own
    // visibility so only the playing engine's picture shows.
    val videoContainer = JPanel().apply { layout = OverlayLayout(this) }
    val webViewJs = withContext(Dispatchers.IO) { readWebViewJs() }

    val player = withContext(Dispatchers.Main) {
        createPlayer(
            PlayerEngines(
                local = { createLocalPlayer(LocalPlayerContext(videoContainer)) },
                webView = {
                    createWebViewPlayer(
                        WebViewPlayerContext(cefClient, videoContainer),
                        engineJs = webViewJs,
                        appVersion = 1,
                        // The embed fetch is a browser-identity request — it rides the platform
                        // cookie store like the WebView's own traffic (unlike the engine client).
                        httpClient = KmpHttpClient.create { cookieJar = platformCookies },
                    )
                },
            ),
        )
    }

    return AppGraph(
        engines,
        player,
        mediaStore = mediaStore,
        scanLibraryFolder = { scanPickedFolder(mediaStore) },
        appVersion = "dev · jvm",
        imageLoader = imageLoader,
        settings = settings,
        // The OSR browser renders a fixed logical viewport — safe to display at art-tile size.
        surfaceScalesDown = true,
    ) { modifier ->
        SwingPanel(factory = { videoContainer }, modifier = modifier)
    }
}

/** FileDialog pick + full scan into [store]; returns the summary line, or null when the pick was canceled. */
private suspend fun scanPickedFolder(store: BetterMediaStore): String? {
    val picked = pickFolder() ?: return null
    val ffmpeg = findFfmpeg()
        ?: return "ffmpeg not found — install it (e.g. brew install ffmpeg) to scan metadata"
    val scanner = MediaScanner.create(
        store,
        FileSystemDocumentsProvider(FileSystem.SYSTEM),
        FfmpegMetadataExtractor(FfmpegMetadataGetter(ffmpeg)),
    )
    val rootUri = UriBuilder().scheme("file").authority("").path(picked.absolutePath).build()
    val progress = scanner.scan(rootUri).last()
    progress.errors.forEach { println("scan error at ${it.uri}: ${it.cause}") }
    return buildString {
        append("Scanned ${progress.scanned}: ${progress.added} added, ${progress.updated} updated, ")
        append("${progress.deleted} removed")
        if (progress.errors.isNotEmpty()) append(", ${progress.errors.size} failed")
    }
}

// macOS's AWT FileDialog only picks directories while this property is set; elsewhere the dialog
// picks a single file, which the scanner indexes on its own (a supported single-file scan).
private suspend fun pickFolder(): File? = withContext(Dispatchers.Main) {
    val prop = "apple.awt.fileDialogForDirectories"
    val previous = System.getProperty(prop)
    System.setProperty(prop, "true")
    try {
        val dialog = FileDialog(null as Frame?, "Choose a music folder", FileDialog.LOAD)
        dialog.isVisible = true
        dialog.file?.let { File(dialog.directory, it) }
    } finally {
        if (previous == null) System.clearProperty(prop) else System.setProperty(prop, previous)
    }
}

private fun findFfmpeg(): String? {
    System.getenv("YMUSIC_FFMPEG")?.let { if (File(it).canExecute()) return it }
    val dirs = System.getenv("PATH").orEmpty().split(File.pathSeparator) +
        listOf("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin")
    return dirs.asSequence().map { File(it, "ffmpeg") }.firstOrNull { it.canExecute() }?.absolutePath
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
