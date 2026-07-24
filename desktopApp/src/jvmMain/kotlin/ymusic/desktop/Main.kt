package ymusic.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kmpcommon.uri.Uri
import kmpcommon.uri.UriBuilder
import kmpffmpeg.metadata.FfmpegMetadataGetter
import kmphttp.KmpHttpClient
import kmphttp.create
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
import org.cef.browser.JcefOsrSurface
import org.jetbrains.compose.resources.painterResource
import ymusic.dynamicengine.DynamicEngineManager
import ymusic.dynamicengine.desktopEngineCacheStore
import ymusic.imageloader.DiskScaledImageCache
import ymusic.imageloader.ImageLoader
import ymusic.imageloader.defaultMemoryCacheBudget
import ymusic.imageloader.fileFetcher
import ymusic.imageloader.networkFetcher
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
import ymusic.ui.localArtFetcher
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
        var graph by remember { mutableStateOf<AppGraph?>(null) }
        Window(
            onCloseRequest = {
                cefManager.dispose()
                exitApplication()
            },
            title = "YMusic",
            icon = painterResource(Res.drawable.ymusic_logo),
            state = rememberWindowState(size = DpSize(480.dp, 860.dp)),
            onPreviewKeyEvent = { event ->
                val refresh = event.type == KeyEventType.KeyDown &&
                    (event.key == Key.F5 || ((event.isMetaPressed || event.isCtrlPressed) && event.key == Key.R))
                if (refresh) graph?.requestRefresh()
                refresh
            },
        ) {
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
    val platformCookies = JcefCookieJar(cefManager.awaitCookieManager())
    val engineClient = KmpHttpClient.create { }
    val settings = FileSettingsStore.open(File(dataDir, "settings.properties").absolutePath.toPath())
    val engines = withContext(Dispatchers.IO) {
        DynamicEngineManager(
            engineClient,
            appVersion = 1,
            logger = StdoutLogger,
            cacheStore = desktopEngineCacheStore(File(dataDir, "engine-cache")),
            cookieJar = platformCookies,
            settings = settings,
        )
    }
    engines.start()

    val mediaStore = withContext(Dispatchers.IO) {
        BetterMediaStore.create(mediaStoreJdbcDriver(File(dataDir, "media-store.db").absolutePath))
    }

    // Thumbnails: everything is a fetcher — http on a cache-less client (originals stream to the
    // fetcher's disk cache, decode from file) + local files + ffmpeg-extracted embedded art
    // (absent ffmpeg just keeps art:// urls unresolved → placeholder boxes).
    val imageLoader = ImageLoader(
        fetchers = listOfNotNull(
            networkFetcher(
                KmpHttpClient.create { },
                FileSystem.SYSTEM,
                File(dataDir, "image-source-cache").absolutePath.toPath(),
            ),
            fileFetcher(),
            findFfmpeg()?.let { localArtFetcher(FfmpegMetadataGetter(it), mediaStore) },
        ),
        scaledCache = DiskScaledImageCache(FileSystem.SYSTEM, File(dataDir, "image-scaled-cache").absolutePath.toPath()),
        logger = StdoutLogger,
        memoryCacheBytes = defaultMemoryCacheBudget(),
    )

    val vlcContainer = JPanel().apply { layout = OverlayLayout(this) }
    val webViewSurface = JcefOsrSurface()
    val webViewJs = withContext(Dispatchers.IO) { readWebViewJs() }

    val player = withContext(Dispatchers.Main) {
        createPlayer(
            PlayerEngines(
                local = { createLocalPlayer(LocalPlayerContext(vlcContainer)) },
                webView = {
                    createWebViewPlayer(
                        WebViewPlayerContext(cefClient, webViewSurface),
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
        scanLibraryFolder = { onProgress -> scanPickedFolder(mediaStore, onProgress) },
        rescanLibrary = { onProgress -> rescanKnownRoots(mediaStore, onProgress) },
        appVersion = "dev · jvm",
        imageLoader = imageLoader,
        settings = settings,
        // The OSR browser renders a fixed logical viewport — safe to display at art-tile size.
        surfaceScalesDown = true,
    ) { modifier ->
        DesktopPlayerSurface(vlcContainer, webViewSurface, modifier)
    }
}

@Composable
private fun DesktopPlayerSurface(vlcContainer: JPanel, webViewSurface: JcefOsrSurface, modifier: Modifier) {
    val webVisible by webViewSurface.visible.collectAsState()
    val frame by webViewSurface.frame.collectAsState()
    var size by remember { mutableStateOf(IntSize.Zero) }
    val bitmap = remember(frame) { frame?.toComposeImageBitmap() }

    Box(modifier.onSizeChanged { size = it }) {
        if (!webVisible) {
            SwingPanel(factory = { vlcContainer }, modifier = Modifier.fillMaxSize())
        }
        if (webVisible && bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().pointerInput(webViewSurface, size) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: continue
                            val point = mapToLogical(change.position.x, change.position.y, size, webViewSurface) ?: continue
                            when (event.type) {
                                PointerEventType.Press -> webViewSurface.mouse(
                                    java.awt.event.MouseEvent.MOUSE_PRESSED,
                                    point.first,
                                    point.second,
                                    java.awt.event.MouseEvent.BUTTON1,
                                    clickCount = 1,
                                )
                                PointerEventType.Release -> webViewSurface.mouse(
                                    java.awt.event.MouseEvent.MOUSE_RELEASED,
                                    point.first,
                                    point.second,
                                    java.awt.event.MouseEvent.BUTTON1,
                                    clickCount = 1,
                                )
                                PointerEventType.Scroll -> webViewSurface.wheel(
                                    point.first,
                                    point.second,
                                    change.scrollDelta.y.toInt().coerceIn(-1, 1),
                                )
                                else -> webViewSurface.mouse(
                                    java.awt.event.MouseEvent.MOUSE_MOVED,
                                    point.first,
                                    point.second,
                                )
                            }
                        }
                    }
                },
            )
        }
    }
}

private fun mapToLogical(x: Float, y: Float, size: IntSize, surface: JcefOsrSurface): Pair<Int, Int>? {
    if (size.width <= 0 || size.height <= 0) return null
    val logical = surface.logicalSize
    val scale = minOf(size.width.toDouble() / logical.width, size.height.toDouble() / logical.height)
    val left = (size.width - logical.width * scale) / 2.0
    val top = (size.height - logical.height * scale) / 2.0
    if (x < left || x >= left + logical.width * scale || y < top || y >= top + logical.height * scale) return null
    return ((x - left) / scale).toInt() to ((y - top) / scale).toInt()
}

/** FileDialog pick + full scan into [store]; returns the summary line, or null when the pick was canceled. */
private suspend fun scanPickedFolder(store: BetterMediaStore, onProgress: (Int) -> Unit): String? {
    val picked = pickFolder() ?: return null
    val rootUri = UriBuilder().scheme("file").authority("").path(picked.absolutePath).build()
    return scanUris(store, listOf(rootUri), onProgress)
}

/** Re-scans every already-added root folder in place (no pick), reconciling deletions/edits. */
private suspend fun rescanKnownRoots(store: BetterMediaStore, onProgress: (Int) -> Unit): String? {
    val roots = store.childBuckets(null)
    if (roots.isEmpty()) return "No folders to rescan — add one first"
    return scanUris(store, roots.map { it.uri }, onProgress)
}

/** Scans each root [uris] into [store], reporting the running file count through [onProgress] and
 *  returning one combined summary line. */
private suspend fun scanUris(store: BetterMediaStore, uris: List<Uri>, onProgress: (Int) -> Unit): String? {
    val ffmpeg = findFfmpeg()
        ?: return "ffmpeg not found — install it (e.g. brew install ffmpeg) to scan metadata"
    val scanner = MediaScanner.create(
        store,
        FileSystemDocumentsProvider(FileSystem.SYSTEM),
        FfmpegMetadataExtractor(FfmpegMetadataGetter(ffmpeg)),
    )
    var scanned = 0
    var added = 0
    var updated = 0
    var deleted = 0
    var failed = 0
    for (uri in uris) {
        val progress = scanner.scan(uri).onEach { onProgress(scanned + it.scanned) }.last()
        progress.errors.forEach { println("scan error at ${it.uri}: ${it.cause}") }
        scanned += progress.scanned
        added += progress.added
        updated += progress.updated
        deleted += progress.deleted
        failed += progress.errors.size
    }
    return buildString {
        append("Scanned $scanned: $added added, $updated updated, $deleted removed")
        if (failed > 0) append(", $failed failed")
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

internal fun findFfmpeg(): String? {
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
