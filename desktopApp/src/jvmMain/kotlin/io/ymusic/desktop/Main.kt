package io.ymusic.desktop

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
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.sentry.kotlin.multiplatform.Sentry
import io.ymusic.desktop.media.MacDock
import io.ymusic.desktop.media.connectMediaSession
import io.ymusic.dynamicengine.DynamicEngineManager
import io.ymusic.dynamicengine.desktopEngineCacheStore
import io.ymusic.dynamicengine.yt.embedPage
import io.ymusic.imageloader.DiskScaledImageCache
import io.ymusic.imageloader.ImageLoader
import io.ymusic.imageloader.defaultMemoryCacheBudget
import io.ymusic.imageloader.fileFetcher
import io.ymusic.imageloader.networkFetcher
import io.ymusic.mediascanner.FfmpegMetadataExtractor
import io.ymusic.mediascanner.FileSystemDocumentsProvider
import io.ymusic.mediascanner.MediaScanner
import io.ymusic.mediastore.BetterMediaStore
import io.ymusic.mediastore.mediaStoreJdbcDriver
import io.ymusic.mediastore.model.BucketDraft
import io.ymusic.player.LocalPlayerContext
import io.ymusic.player.LocalVideoSurface
import io.ymusic.player.PlayerEngines
import io.ymusic.player.WebViewPlayerContext
import io.ymusic.player.createLocalPlayer
import io.ymusic.player.createPlayer
import io.ymusic.player.createWebViewPlayer
import io.ymusic.settings.AppSettings
import io.ymusic.settings.FileSettingsStore
import io.ymusic.settings.flow
import io.ymusic.settings.put
import io.ymusic.telemetry.SentryLogger
import io.ymusic.telemetry.applyYMusicTelemetry
import io.ymusic.telemetry.installSentryDeviceId
import io.ymusic.telemetry.sendSentryFeedback
import io.ymusic.ui.App
import io.ymusic.ui.AppGraph
import io.ymusic.ui.AppHttpClients
import io.ymusic.ui.WebViewSabrSpecResolver
import io.ymusic.ui.downloadCaptionPreference
import io.ymusic.ui.downloadLocationFolder
import io.ymusic.ui.ensureAppLanguage
import io.ymusic.ui.mediaStoreArtFetcher
import io.ymusic.ui.registerCompletedDownload
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.ymusic_logo
import io.ymusic.ytmedia.cache.DiskMediaBlobCache
import io.ymusic.ytmedia.cache.MediaCache
import io.ymusic.ytmedia.cache.SqlDelightBlobIndex
import io.ymusic.ytmedia.cache.SqlDelightSegmentIndex
import io.ymusic.ytmedia.cache.jvmSegmentDatabaseDriver
import io.ymusic.ytmedia.proxy.LocalMediaProxy
import kmpcommon.log.Logger
import kmpcommon.log.TeeLogger
import kmpcommon.uri.Uri
import kmpcommon.uri.UriBuilder
import kmpcommon.uri.filePath
import kmpffmpeg.metadata.FfmpegMetadataGetter
import kmphttp.KmpHttpClient
import kmphttp.RedirectOptions
import kmphttp.create
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
import org.cef.browser.JcefOsrSurface
import org.jetbrains.compose.resources.painterResource
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * The desktop shell: boots JCEF + the scraping engine, assembles the [AppGraph] (player facade over
 * VLCJ local + JCEF WebView engines, the shared WebView container as the video surface), and runs
 * the shared [App].
 */
// Sentry logs/captures (no-op when Sentry wasn't initialized) teed with stdout.
private val rootLogger: Logger = TeeLogger(SentryLogger(), StdoutLogger())

fun main() {
    if (SENTRY_DSN.isNotEmpty()) {
        Sentry.init {
            it.applyYMusicTelemetry()
            it.dsn = SENTRY_DSN
            // The commit in the release traces an event to git history (no source upload).
            it.release = SENTRY_RELEASE
        }
    }
    // Both video engines (JCEF WebView + VLCJ local) now render off-screen and paint through a plain
    // Compose Image, so the app hosts no Swing interop on the hot path. Blending is kept as a harmless
    // safety net for any incidental lightweight interop; it must be set before the first ComposeWindow.
    System.setProperty("compose.interop.blending", "true")
    // Stop-signal and shutdown resilience against CEF live in CefManager.installShutdownGuards
    // (installed with the first createClient — shared with the probes).
    application {
        val cefManager = remember { CefManager() }
        var graph by remember { mutableStateOf<AppGraph?>(null) }
        var windowVisible by remember { mutableStateOf(true) }
        val (initialSize, initialPosition) = remember { loadWindowPrefs() }
        val windowState = rememberWindowState(size = initialSize, position = initialPosition)
        fun quit() {
            // Flush the geometry synchronously — a move/resize inside the debounce window would
            // otherwise be lost with the cancelled save (every exit path funnels through here).
            if (windowState.placement == WindowPlacement.Floating) {
                saveWindowPrefs(windowState.size, windowState.position)
            }
            cefManager.dispose()
            exitApplication()
        }
        // Persist the size/position the user settles on (debounced past the live drag/resize
        // churn); a maximized/fullscreen geometry is the placement's, not the user's chosen
        // floating one, so it's skipped.
        LaunchedEffect(windowState) {
            snapshotFlow { Triple(windowState.size, windowState.position, windowState.placement) }
                .collectLatest { (size, position, placement) ->
                    delay(500)
                    if (placement == WindowPlacement.Floating) {
                        withContext(Dispatchers.IO) { saveWindowPrefs(size, position) }
                    }
                }
        }
        Window(
            visible = windowVisible,
            onCloseRequest = {
                // A non-empty queue → the close button minimizes to the system tray and playback
                // carries on (the OSR browser renders to its own fixed viewport, not the window);
                // an empty queue → quit.
                if (graph?.player?.queue?.value?.items?.isNotEmpty() == true) windowVisible = false else quit()
            },
            title = "YMusic",
            icon = painterResource(Res.drawable.ymusic_logo),
            state = windowState,
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
                } catch (c: kotlinx.coroutines.CancellationException) {
                    throw c // the effect was disposed — not a boot failure
                } catch (t: Throwable) {
                    rootLogger.fork("boot").captureError(t)
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
        // The tray icon lives whenever there is a queue to control — window open or not — and
        // always while the window is hidden (it is the only way back then). A plain click opens
        // the notification-style mini player anchored under the icon; no context menu.
        var miniOpen by remember { mutableStateOf(false) }
        var miniAnchorX by remember { mutableStateOf<Int?>(null) }
        var queueNotEmpty by remember { mutableStateOf(false) }
        LaunchedEffect(graph) { graph?.player?.queue?.collect { queueNotEmpty = it.items.isNotEmpty() } }
        if (queueNotEmpty || !windowVisible) {
            PlayerTray(painterResource(Res.drawable.ymusic_logo)) { screenX ->
                miniAnchorX = screenX
                miniOpen = !miniOpen
            }
        }
        val g = graph
        if (miniOpen && g != null) {
            MiniPlayerWindow(
                graph = g,
                anchorX = miniAnchorX,
                onOpenApp = {
                    windowVisible = true
                    miniOpen = false
                },
                onDismiss = {
                    // End the listening session: the queue clears (which retires the tray icon),
                    // and with no window left the app exits; an open window keeps running.
                    miniOpen = false
                    g.player.clearQueue()
                    if (!windowVisible) quit()
                },
                onClose = { miniOpen = false },
            )
        }
        // Tray-only mode also drops the Dock icon (macOS accessory activation); restoring the
        // window brings it back and re-activates the app.
        LaunchedEffect(windowVisible) { MacDock.setDockIconVisible(windowVisible, rootLogger.fork("mac-dock")) }
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
    val http = AppHttpClients(File(dataDir, "http-cache").absolutePath.toPath(), logger = rootLogger)
    val settings = FileSettingsStore.open(File(dataDir, "settings.properties").absolutePath.toPath())
    // First launch decides the app language (device if supported, else English) and persists it —
    // BEFORE the engine starts, because the engine pulls the same key as its content-language (hl).
    ensureAppLanguage(settings)
    // Stable anonymous crash-reporting id: the persisted copy wins; a fresh (or wiped) install
    // re-derives the same machine-seeded hash, so the id survives an app-data clear.
    val deviceId = settings.flow(AppSettings.deviceId).value.ifEmpty {
        (machineSeededDeviceId(rootLogger.fork("telemetry")) ?: java.util.UUID.randomUUID().toString())
            .also { settings.put(AppSettings.deviceId, it) }
    }
    installSentryDeviceId(deviceId)
    val engines = withContext(Dispatchers.IO) {
        DynamicEngineManager(
            http.engine,
            appVersion = 1,
            logger = rootLogger,
            cacheStore = desktopEngineCacheStore(File(dataDir, "engine-cache"), rootLogger),
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
    val imageNetworkFetcher = networkFetcher(
        http.image,
        FileSystem.SYSTEM,
        File(dataDir, "image-source-cache").absolutePath.toPath(),
    )
    val imageLoader = ImageLoader(
        fetchers = listOfNotNull(
            imageNetworkFetcher,
            fileFetcher(),
            findFfmpeg()?.let { mediaStoreArtFetcher(FfmpegMetadataGetter(it), mediaStore, imageNetworkFetcher) },
        ),
        scaledCache = DiskScaledImageCache(FileSystem.SYSTEM, File(dataDir, "image-scaled-cache").absolutePath.toPath()),
        logger = rootLogger,
        memoryCacheBytes = defaultMemoryCacheBudget(),
    )

    val localSurface = LocalVideoSurface()
    val webViewSurface = JcefOsrSurface()

    // Player cache: the WebView bridge intercepts the bundle's /ymusicForward (rewritten from
    // /videoplayback) and serves it from this router — cache hit → synthesized bytes, miss → forwarded
    // and teed to disk so re-plays/downloads reuse it. Its client carries the CEF cookies + the Chrome
    // UA CefManager forces on the WebView; a mismatched identity gets rejected by googlevideo/BotGuard.
    // The player cache; shared read-only with the download stack so a download reuses segments already
    // cached from playback (never writing into it).
    val playerCache = withContext(Dispatchers.IO) {
        val cacheRoot = File(dataDir, "player-cache").apply { mkdirs() }
        // One persistent DB for both the blob-span index and the segment index.
        val driver = jvmSegmentDatabaseDriver("jdbc:sqlite:${File(cacheRoot, "cache-index.db").absolutePath}")
        MediaCache(
            DiskMediaBlobCache(cacheRoot.absolutePath.toPath(), FileSystem.SYSTEM, SqlDelightBlobIndex(driver, now = { System.currentTimeMillis() })),
            SqlDelightSegmentIndex(driver, now = { System.currentTimeMillis() }),
        )
    }
    val mediaProxy = withContext(Dispatchers.IO) {
        val client = KmpHttpClient.create {
            logger = rootLogger.fork("kmphttp")
            cookieJar = platformCookies
            defaultRedirectOptions = RedirectOptions(followRedirects = true, followSslRedirects = true)
        }
        LocalMediaProxy(
            playerCache, client,
            userAgent = CefManager.CHROME_USER_AGENT,
            logger = rootLogger,
            // Chatty per-part/decision logs; -Dymusic.player.verbose=false to quiet them (or flip at runtime).
            verbose = System.getProperty("io.ymusic.player.verbose", "true").toBoolean(),
            // -Dymusic.player.serveSabrHits=false → fill-only, to test whether synthesized hits wedge switches.
            serveSabrHits = System.getProperty("io.ymusic.player.serveSabrHits", "true").toBoolean(),
        )
    }

    // Captured from the WebView-engine factory so the download service can prime SABR inputs off the
    // same page (attestation lives in the running page). Recreated on "Reload player"; latest wins.
    var downloadWebViewPlayer: io.ymusic.player.WebViewPlayer? = null
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
                        logger = rootLogger,
                    ).also { downloadWebViewPlayer = it }
                },
            ),
        )
    }

    // OS media surface — macOS Now Playing widget + media keys (Linux MPRIS / Windows SMTC have no
    // backend yet). Commands come back on the Main dispatcher, where the player is driven.
    connectMediaSession(player, CoroutineScope(Dispatchers.Main), rootLogger.fork("now-playing"))

    // Warm the YouTube engine ~5s after startup so the first play/download skips the WebView + /embed
    // build (mirrors legacy warmUpYtPlayer). Off the boot path; on the EDT since it hosts JCEF.
    CoroutineScope(Dispatchers.Default).launch {
        delay(5_000)
        withContext(Dispatchers.Main) { player.prepareYoutubeEngine() }
    }

    // Offline downloads: reuse the SABR download stack over the prebuilt ffmpeg. The spec resolver drives
    // the playback WebView to prime each video's player response + PO token (so a download of a video the
    // user isn't already playing briefly loads it into the player — a known first-cut limitation).
    val downloadService = withContext(Dispatchers.IO) {
        val ffmpegPath = locateDesktopFfmpeg()
        val ffmpeg = ffmpegPath?.let { io.ymusic.download.Ffmpeg(it) }
        // Probes a finished download's metadata into its Library bucket (same path as the folder scan);
        // null without ffmpeg → registerCompletedDownload falls back to a hand-built draft.
        val downloadScanner = ffmpegPath?.let {
            MediaScanner.create(
                mediaStore,
                FileSystemDocumentsProvider(FileSystem.SYSTEM),
                FfmpegMetadataExtractor(FfmpegMetadataGetter(it)),
            )
        }
        val store = io.ymusic.download.DownloadStore(
            io.ymusic.download.downloadDatabaseJdbcDriver("jdbc:sqlite:${File(dataDir, "downloads.db").absolutePath}"),
            rootLogger,
        )
        val sabrClient = KmpHttpClient.create {
            logger = rootLogger.fork("kmphttp")
            cookieJar = platformCookies
            defaultRedirectOptions = RedirectOptions(followRedirects = false, followSslRedirects = false)
        }
        val resolver = io.ymusic.download.SabrSpecResolver { videoId ->
            // Force-init the WebView engine if it isn't up yet (the factory sets downloadWebViewPlayer).
            val page = downloadWebViewPlayer ?: run {
                withContext(Dispatchers.Main) { player.prepareYoutubeEngine() }
                downloadWebViewPlayer
            } ?: error("YouTube engine unavailable")
            WebViewSabrSpecResolver(page).resolve(videoId)
        }
        // Own segment index (byte range per segment) → SABR resume across pause/re-attest. Separate from
        // the player cache DB: downloads store bytes in their own file, not the player blob cache.
        val segmentIndex = SqlDelightSegmentIndex(
            jvmSegmentDatabaseDriver("jdbc:sqlite:${File(dataDir, "download-segments.db").absolutePath}"),
            now = { System.currentTimeMillis() },
        )
        io.ymusic.download.DownloadService(
            store = store,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            now = { System.currentTimeMillis() },
            resolver = resolver,
            client = sabrClient,
            userAgent = CefManager.CHROME_USER_AGENT,
            ffmpeg = ffmpeg,
            storage = io.ymusic.download.DefaultDownloadStorage(
                FileSystem.SYSTEM,
                File(dataDir, "download-work").absolutePath.toPath(),
            ),
            mediaCache = playerCache, // read-only (see YtStreamDownloader): reuse cached playback bytes
            segmentIndex = segmentIndex,
            captionPreference = { settings.downloadCaptionPreference() },
            // The user's chosen download location — always set: the download flow requires picking one
            // before enqueue.
            downloadFolder = { settings.downloadLocationFolder()!! },
            onCompleted = { task, result -> registerCompletedDownload(mediaStore, settings, downloadScanner, task, result) },
            logger = rootLogger,
        )
    }

    return AppGraph(
        engines,
        player,
        logger = rootLogger,
        mediaStore = mediaStore,
        scanLibraryFolder = { onProgress -> scanPickedFolder(mediaStore, onProgress) },
        libraryFolderPicker = { pickAndRegisterFolder(mediaStore) },
        rescanLibrary = { onProgress -> rescanKnownRoots(mediaStore, onProgress) },
        deleteFromDisk = { item ->
            withContext(Dispatchers.IO) {
                check(item.uri.scheme.contentEquals("file")) { "not a local file: ${item.uri}" }
                // mustExist=false: an already-gone file still clears its library row.
                FileSystem.SYSTEM.delete(item.uri.path.toString().toPath(), mustExist = false)
            }
        },
        revealInFolder = { uri ->
            withContext(Dispatchers.IO) {
                val path = Uri.parse(uri).filePath()
                runCatching { ProcessBuilder("open", "-R", path).start() } // mac Finder reveal
                    .onFailure { rootLogger.fork("reveal").log(Logger.Level.WARN, "Finder reveal failed for $path", it) }
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
        sendFeedback = sendFeedbackAction.takeIf { SENTRY_DSN.isNotEmpty() },
        pickFeedbackFiles = ::pickFeedbackFiles,
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

/** Pick a folder and register it as a Library bucket (no media scan — works even when empty), returning
 *  its uri. Used to choose a download location. */
private suspend fun pickAndRegisterFolder(store: BetterMediaStore): String? {
    val picked = pickFolder() ?: return null
    val uri = UriBuilder().scheme("file").authority("").path(picked.absolutePath).build()
    store.upsertBucket(BucketDraft(uri = uri, name = picked.name))
    return uri.toString()
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
        progress.errors.forEach { rootLogger.fork("library-scan").log(Logger.Level.WARN, "scan error at ${it.uri}", it.cause) }
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

// The window-geometry memory lives in its OWN tiny file, read synchronously before the first
// frame — the settings store only exists once the async graph build runs, too late to size the
// window.
private val windowPrefsFile = File(System.getProperty("user.home"), ".ymusic/window.properties")
private val defaultWindowSize = DpSize(480.dp, 860.dp)

private fun loadWindowPrefs(): Pair<DpSize, WindowPosition> {
    val p = java.util.Properties()
    runCatching { windowPrefsFile.inputStream().use(p::load) } // no file yet / unreadable → defaults
    val w = p.getProperty("width")?.toFloatOrNull()
    val h = p.getProperty("height")?.toFloatOrNull()
    // Sanity floor: never restore a degenerate size a bad write (or a past bug) left behind.
    val size = if (w != null && h != null && w >= 320f && h >= 480f) DpSize(w.dp, h.dp) else defaultWindowSize
    val x = p.getProperty("x")?.toFloatOrNull()
    val y = p.getProperty("y")?.toFloatOrNull()
    val position = when {
        x == null || y == null -> WindowPosition.PlatformDefault
        // The saved spot may be gone (monitor unplugged, resolution change) — a window whose title
        // bar lands on no screen can't be grabbed back, so it resets to center instead.
        isOnScreen(x, y) -> WindowPosition(x.dp, y.dp)
        else -> WindowPosition.Aligned(Alignment.Center)
    }
    return size to position
}

/** True when a title-bar point of a window at ([x], [y]) lands on some connected screen. */
private fun isOnScreen(x: Float, y: Float): Boolean = runCatching {
    java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.any { device ->
        val b = device.defaultConfiguration.bounds
        x + 100 > b.x && x + 100 < b.x + b.width && y + 20 > b.y && y + 20 < b.y + b.height
    }
}.getOrDefault(true) // can't enumerate screens → don't block the restore

private fun saveWindowPrefs(size: DpSize, position: WindowPosition) {
    val p = java.util.Properties()
    p.setProperty("width", size.width.value.toString())
    p.setProperty("height", size.height.value.toString())
    if (position.isSpecified) {
        p.setProperty("x", position.x.value.toString())
        p.setProperty("y", position.y.value.toString())
    }
    try {
        windowPrefsFile.parentFile?.mkdirs()
        windowPrefsFile.outputStream().use { p.store(it, null) }
    } catch (e: java.io.IOException) {
        // Environmental (disk full, permissions) — the window still works, only the memory is lost.
        rootLogger.fork("window").log(Logger.Level.WARN, "window prefs save failed", e)
    }
}

/** Delivers a feedback report as Sentry user feedback; attachments ride the anchor event. */
private val sendFeedbackAction: suspend (io.ymusic.ui.FeedbackDraft) -> Unit = { draft ->
    withContext(Dispatchers.IO) {
        sendSentryFeedback(
            comments = draft.message,
            name = draft.name,
            email = draft.email,
            attachments = draft.attachments.mapNotNull { f ->
                f.bytes?.let { b -> io.sentry.kotlin.multiplatform.Attachment(b, f.filename) }
            },
        )
    }
}

/** Multi-select file pick for feedback attachments; an over-limit file comes back name-only (null
 *  bytes) so the form can refuse it with its name. */
private suspend fun pickFeedbackFiles(): List<io.ymusic.ui.FeedbackFile> {
    val picked = withContext(Dispatchers.Main) {
        val dialog = FileDialog(null as Frame?, "Attach files", FileDialog.LOAD)
        dialog.isMultipleMode = true
        dialog.isVisible = true
        dialog.files.toList()
    }
    return withContext(Dispatchers.IO) {
        picked.map { file ->
            val size = file.length()
            if (size > io.ymusic.ui.FeedbackFile.MAX_BYTES) io.ymusic.ui.FeedbackFile(file.name, bytes = null, sizeBytes = size)
            else io.ymusic.ui.FeedbackFile(file.name, file.readBytes())
        }
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
    System.getProperty("io.ymusic.ffmpeg")?.let { return File(it).takeIf { f -> f.exists() }?.absolutePath }
    val os = System.getProperty("os.name").lowercase()
    val rel = when {
        os.contains("mac") -> "ffmpeg-prebuilt-ymusic/macos/ffmpeg"
        os.contains("linux") -> "ffmpeg-prebuilt-ymusic/linux-x86_64/ffmpeg"
        else -> return null
    }
    return listOf(rel, "../$rel").map(::File).firstOrNull { it.exists() }?.absolutePath
}
