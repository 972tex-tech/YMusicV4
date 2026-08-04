package ymusic.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kmpcommon.uri.Uri
import kmpcommon.uri.UriBuilder
import kmpffmpeg.metadata.FfmpegMetadataGetter
import kmphttp.KmpHttpClient
import kmphttp.RedirectOptions
import kmphttp.create
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
import org.cef.browser.JcefOsrSurface
import org.jetbrains.compose.resources.painterResource
import ymusic.dynamicengine.DynamicEngineManager
import ymusic.dynamicengine.desktopEngineCacheStore
import ymusic.dynamicengine.yt.embedPage
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
import ymusic.player.LocalVideoSurface
import ymusic.player.PlayerEngines
import ymusic.player.WebViewPlayerContext
import ymusic.player.createLocalPlayer
import ymusic.player.createPlayer
import ymusic.player.createWebViewPlayer
import ymusic.settings.FileSettingsStore
import ymusic.ui.App
import ymusic.ui.AppGraph
import ymusic.ui.AppHttpClients
import ymusic.ui.WebViewSabrSpecResolver
import ymusic.ui.ensureAppLanguage
import ymusic.ui.localArtFetcher
import ymusic.ui.resources.Res
import ymusic.ui.resources.ymusic_logo
import ymusic.ytmedia.cache.DiskMediaBlobCache
import ymusic.ytmedia.cache.MediaCache
import ymusic.ytmedia.cache.SqlDelightBlobIndex
import ymusic.ytmedia.cache.SqlDelightSegmentIndex
import ymusic.ytmedia.cache.jvmSegmentDatabaseDriver
import ymusic.ytmedia.proxy.LocalMediaProxy
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * The desktop shell: boots JCEF + the scraping engine, assembles the [AppGraph] (player facade over
 * VLCJ local + JCEF WebView engines, the shared WebView container as the video surface), and runs
 * the shared [App].
 */
fun main() {
    // Both video engines (JCEF WebView + VLCJ local) now render off-screen and paint through a plain
    // Compose Image, so the app hosts no Swing interop on the hot path. Blending is kept as a harmless
    // safety net for any incidental lightweight interop; it must be set before the first ComposeWindow.
    System.setProperty("compose.interop.blending", "true")
    // Stop-signal and shutdown resilience against CEF live in CefManager.installShutdownGuards
    // (installed with the first createClient — shared with the probes).
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

            MenuBar {
                Menu("Debug") {
                    // Enabled once CEF is up (the graph builds right after createClient).
                    Item("Cookie inspector (shared CEF profile)", enabled = graph != null) {
                        openCookieInspector(cefManager)
                    }
                }
            }

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
    // One client stack, one disk cache: the engine (no cookie jar — the scraping JS owns cookie
    // policy) and the player's embed fetch (platform cookies) share it.
    val http = AppHttpClients(File(dataDir, "http-cache").absolutePath.toPath())
    val settings = FileSettingsStore.open(File(dataDir, "settings.properties").absolutePath.toPath())
    // First launch decides the app language (device if supported, else English) and persists it —
    // BEFORE the engine starts, because the engine pulls the same key as its content-language (hl).
    ensureAppLanguage(settings)
    val engines = withContext(Dispatchers.IO) {
        DynamicEngineManager(
            http.engine,
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
                http.image,
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

    val localSurface = LocalVideoSurface()
    val webViewSurface = JcefOsrSurface()

    // Player cache: the WebView bridge intercepts the bundle's /ymusicForward (rewritten from
    // /videoplayback) and serves it from this router — cache hit → synthesized bytes, miss → forwarded
    // and teed to disk so re-plays/downloads reuse it. Its client carries the CEF cookies + the Chrome
    // UA CefManager forces on the WebView; a mismatched identity gets rejected by googlevideo/BotGuard.
    val mediaProxy = withContext(Dispatchers.IO) {
        val cacheRoot = File(dataDir, "player-cache").apply { mkdirs() }
        // One persistent DB for both the blob-span index and the segment index.
        val driver = jvmSegmentDatabaseDriver("jdbc:sqlite:${File(cacheRoot, "cache-index.db").absolutePath}")
        val cache = MediaCache(
            DiskMediaBlobCache(cacheRoot.absolutePath.toPath(), FileSystem.SYSTEM, SqlDelightBlobIndex(driver, now = { System.currentTimeMillis() })),
            SqlDelightSegmentIndex(driver, now = { System.currentTimeMillis() }),
        )
        val client = KmpHttpClient.create {
            cookieJar = platformCookies
            defaultRedirectOptions = RedirectOptions(followRedirects = true, followSslRedirects = true)
        }
        LocalMediaProxy(
            cache, client,
            userAgent = CefManager.CHROME_USER_AGENT,
            logger = StdoutLogger,
            // Chatty per-part/decision logs; -Dymusic.player.verbose=false to quiet them (or flip at runtime).
            verbose = System.getProperty("ymusic.player.verbose", "true").toBoolean(),
            // -Dymusic.player.serveSabrHits=false → fill-only, to test whether synthesized hits wedge switches.
            serveSabrHits = System.getProperty("ymusic.player.serveSabrHits", "true").toBoolean(),
        )
    }

    // Captured from the WebView-engine factory so the download service can prime SABR inputs off the
    // same page (attestation lives in the running page). Recreated on "Reload player"; latest wins.
    var downloadWebViewPlayer: ymusic.player.WebViewPlayer? = null
    val player = withContext(Dispatchers.Main) {
        createPlayer(
            PlayerEngines(
                local = { createLocalPlayer(LocalPlayerContext(localSurface)) },
                // Re-read the bundle on every WebView-engine build so "Reload player" picks up a freshly
                // built webview-js (npm run build) without an app restart.
                webView = {
                    createWebViewPlayer(
                        WebViewPlayerContext(cefClient, webViewSurface, mediaProxy),
                        engineJs = readWebViewJs(),
                        appVersion = 5000,
                        embedHtml = { ua -> engines.embedPage(ua) },
                    ).also { downloadWebViewPlayer = it }
                },
            ),
        )
    }

    // Offline downloads: reuse the SABR download stack over the prebuilt ffmpeg. The spec resolver drives
    // the playback WebView to prime each video's player response + PO token (so a download of a video the
    // user isn't already playing briefly loads it into the player — a known first-cut limitation).
    val downloadService = withContext(Dispatchers.IO) {
        val ffmpeg = locateDesktopFfmpeg()?.let { ymusic.download.Ffmpeg(it) }
        val store = ymusic.download.DownloadStore(
            ymusic.download.downloadDatabaseJdbcDriver("jdbc:sqlite:${File(dataDir, "downloads.db").absolutePath}"),
        )
        val sabrClient = KmpHttpClient.create {
            cookieJar = platformCookies
            defaultRedirectOptions = RedirectOptions(followRedirects = false, followSslRedirects = false)
        }
        val resolver = ymusic.download.SabrSpecResolver { videoId ->
            val page = downloadWebViewPlayer ?: error("play a YouTube item first to enable downloads")
            WebViewSabrSpecResolver(page).resolve(videoId)
        }
        ymusic.download.DownloadService(
            store = store,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            now = { System.currentTimeMillis() },
            resolver = resolver,
            client = sabrClient,
            userAgent = CefManager.CHROME_USER_AGENT,
            ffmpeg = ffmpeg,
            fileSystem = FileSystem.SYSTEM,
            workDir = File(dataDir, "download-work").absolutePath.toPath(),
            outputDir = File(dataDir, "downloads").absolutePath.toPath(),
            logger = StdoutLogger,
        ).also { svc -> CoroutineScope(Dispatchers.Default).launch { svc.load() } }
    }

    return AppGraph(
        engines,
        player,
        mediaStore = mediaStore,
        scanLibraryFolder = { onProgress -> scanPickedFolder(mediaStore, onProgress) },
        rescanLibrary = { onProgress -> rescanKnownRoots(mediaStore, onProgress) },
        deleteFromDisk = { item ->
            withContext(Dispatchers.IO) {
                check(item.uri.scheme.contentEquals("file")) { "not a local file: ${item.uri}" }
                // mustExist=false: an already-gone file still clears its library row.
                FileSystem.SYSTEM.delete(item.uri.path.toString().toPath(), mustExist = false)
            }
        },
        appVersion = "dev · jvm",
        imageLoader = imageLoader,
        settings = settings,
        // The OSR browser renders a fixed logical viewport — safe to display at art-tile size.
        surfaceScalesDown = true,
        loginBrowser = { url, state, onPageChanged, modifier ->
            DesktopLoginBrowser(url, state, onPageChanged, modifier)
        },
        downloads = downloadService,
    ) { modifier ->
        DesktopPlayerSurface(localSurface, webViewSurface, modifier)
    }
}

@Composable
private fun DesktopPlayerSurface(localSurface: LocalVideoSurface, webViewSurface: JcefOsrSurface, modifier: Modifier) {
    val webVisible by webViewSurface.visible.collectAsState()
    val localVisible by localSurface.visible.collectAsState()
    val webFrame by webViewSurface.frame.collectAsState()
    val localFrame by localSurface.frame.collectAsState()
    var size by remember { mutableStateOf(IntSize.Zero) }
    val webBitmap = remember(webFrame) { webFrame?.toComposeImageBitmap() }
    // Both engines render off-screen: the local (VLCJ) picture is a plain Compose Image with no pointer
    // capture, so menus/popups drawn over it receive mouse input — the SwingPanel that used to host it
    // was an AWT interop hole that swallowed those clicks.
    val localBitmap = remember(localFrame) { localFrame?.toComposeImageBitmap() }

    Box(modifier.onSizeChanged { size = it }) {
        if (!webVisible && localVisible) {
            // Active local engine with no frame yet (pre-first-frame or audio-only) shows black, as the
            // old SwingPanel did; once a frame arrives the letterboxed picture paints over it.
            Box(Modifier.fillMaxSize().background(Color.Black))
            if (localBitmap != null) {
                Image(
                    bitmap = localBitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        if (webVisible && webBitmap != null) {
            Image(
                bitmap = webBitmap,
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

/** The prebuilt ffmpeg binary for this host, or null (mp3/video downloads then unavailable). Dev-time:
 *  the repo's ffmpeg-prebuilt-ymusic; override with -Dymusic.ffmpeg=/path. */
private fun locateDesktopFfmpeg(): String? {
    System.getProperty("ymusic.ffmpeg")?.let { return File(it).takeIf { f -> f.exists() }?.absolutePath }
    val os = System.getProperty("os.name").lowercase()
    val rel = when {
        os.contains("mac") -> "ffmpeg-prebuilt-ymusic/macos/ffmpeg"
        os.contains("linux") -> "ffmpeg-prebuilt-ymusic/linux-x86_64/ffmpeg"
        else -> return null
    }
    return listOf(rel, "../$rel").map(::File).firstOrNull { it.exists() }?.absolutePath
}
