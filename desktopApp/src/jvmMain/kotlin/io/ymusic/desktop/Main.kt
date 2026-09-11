package io.ymusic.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.sentry.kotlin.multiplatform.Sentry
import io.ymusic.desktop.media.MacDock
import io.ymusic.desktop.media.connectMediaSession
import io.ymusic.desktop.resources.ymusic_logo_dock
import io.ymusic.desktop.setup.FirstRunWindow
import io.ymusic.desktop.update.DesktopAppUpdates
import io.ymusic.desktop.update.UpdateService
import io.ymusic.dynamicengine.DefaultJsSourceProvider
import io.ymusic.dynamicengine.DynamicEngineManager
import io.ymusic.dynamicengine.desktopEngineCacheStore
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
import io.ymusic.player.createLocalPlayer
import io.ymusic.player.createPlayer
import io.ymusic.remoteconfig.HttpApiService
import io.ymusic.remoteconfig.PersistentCookieJar
import io.ymusic.remoteconfig.RemoteConfig
import io.ymusic.remoteconfig.StatUsage
import io.ymusic.settings.AppSettings
import io.ymusic.settings.FileSettingsStore
import io.ymusic.settings.anonymousDeviceId
import io.ymusic.settings.flow
import io.ymusic.settings.put
import io.ymusic.telemetry.SentryLogger
import io.ymusic.telemetry.applyYMusicTelemetry
import io.ymusic.telemetry.bindCrashReporting
import io.ymusic.telemetry.installSentryDeviceId
import io.ymusic.telemetry.installSentryEngineVersion
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
import org.jetbrains.compose.resources.painterResource
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.math.min
import java.awt.Color as AwtColor

/**
 * The desktop shell: boots the scraping engine, assembles the [AppGraph] (player facade over VLCJ
 * local + MSE YouTube playback), and runs the shared [App].
 */
// Sentry logs/captures (no-op when Sentry wasn't initialized) teed with stdout.
private val rootLogger: Logger = TeeLogger(SentryLogger(), StdoutLogger())

/** Mirrors AppSettings.autoDownloadUpdates for the updater's synchronous read. */
@Volatile private var autoDownloadUpdates: Boolean = false

fun main() {
    // Opened before anything else: the first run has not happened yet at this point, and both the
    // crash-reporting consent and whether to show the setup window are answers held in here.
    val settings = kotlinx.coroutines.runBlocking {
        val dir = ymusicDataDir().apply { mkdirs() }
        FileSettingsStore.open(File(dir, "settings.properties").absolutePath.toPath()).also {
            // First launch decides the app language (device if supported, else English) and
            // persists it — the setup window needs it as much as the app does.
            ensureAppLanguage(it)
        }
    }
    // Whether this launch is dark, decided before any window exists: it drives both the OS window
    // decorations and the colour the frame wears before Compose has drawn a thing.
    val darkChrome = settings.flow(AppSettings.theme).value != "light"
    applyWindowChrome(darkChrome)
    // Crash reporting is consent-gated and LIVE: init runs once (crash handlers install and
    // stay), but nothing leaves the process unless the user's answer — asked during setup,
    // changeable in Settings — says yes. An un-completed setup is silent by construction, and
    // saying yes during setup takes effect in the same session, no restart.
    if (SENTRY_DSN.isNotEmpty()) {
        val telemetryDeviceId = kotlinx.coroutines.runBlocking { settings.anonymousDeviceId() }
        bindCrashReporting(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            consent = combine(
                settings.flow(AppSettings.crashReports),
                settings.flow(AppSettings.setupCompleted),
            ) { consent, setupDone -> consent && setupDone },
        ) {
            Sentry.init {
                it.applyYMusicTelemetry()
                it.dsn = SENTRY_DSN
                // The commit in the release traces an event to git history (no source upload).
                it.release = SENTRY_RELEASE
                it.environment = if (DEBUG_BUILD) "debug" else "production"
            }
            installSentryDeviceId(telemetryDeviceId)
        }
    }
    // MSE video is a SwingPanel (same blit as the playground). Blending must be set before the first
    // ComposeWindow so Compose chrome over the hole still composites. Local VLCJ stays a Compose Image.
    System.setProperty("compose.interop.blending", "true")
    application {
        // The macOS Dock ignores Window(icon=) and, outside a bundle (a dev run), shows the JVM's
        // coffee cup. One 1024px rasterization of the tile vector goes to the Taskbar API — and
        // the DENSITY is the load-bearing part: VectorPainter caches at its intrinsic size
        // (declared 192dp × density), so Density(1f) had every render upscaling a 192px cache.
        // 1024/192 makes the cache itself 1024. desktopApp/ymusic.icns is the packaged twin.
        val dockTile = painterResource(io.ymusic.desktop.resources.Res.drawable.ymusic_logo_dock)
        LaunchedEffect(dockTile) {
            runCatching {
                if (java.awt.Taskbar.isTaskbarSupported()) {
                    val taskbar = java.awt.Taskbar.getTaskbar()
                    if (taskbar.isSupported(java.awt.Taskbar.Feature.ICON_IMAGE)) {
                        taskbar.iconImage = dockTile.toAwtImage(
                            Density(1024f / 192f),
                            LayoutDirection.Ltr,
                            androidx.compose.ui.geometry.Size(1024f, 1024f),
                        )
                    }
                }
            }
        }
        var setupDone by remember { mutableStateOf(settings.flow(AppSettings.setupCompleted).value) }
        if (!setupDone) {
            // Nothing plays until the engines are on disk, so the app's own window does not open
            // until the checklist passes — building the graph first would just fail slowly.
            FirstRunWindow(
                settings = settings,
                dataDir = ymusicDataDir(),
                logger = rootLogger,
                toolsManifestUrl = TOOLS_MANIFEST_URL,
                publicKey = UPDATE_PUBLIC_KEY,
                existingFfmpeg = {
                    // -Dio.ymusic.setup.forceDownload hides a locally-present ffmpeg so the
                    // provisioning path can be exercised (see -Pymusic.setup.forceDownload).
                    if (System.getProperty("io.ymusic.setup.forceDownload").toBoolean()) null
                    else resolveFfmpeg()?.let(::File)
                },
                pickFolder = { pickFolder()?.absolutePath },
                onOpenUrl = ::openInBrowser,
                onFinished = { setupDone = true },
            )
            return@application
        }
        var graph by remember { mutableStateOf<AppGraph?>(null) }
        // Hoisted out of the window: whether the window may show at all depends on it.
        var bootError by remember { mutableStateOf<String?>(null) }
        var windowVisible by remember { mutableStateOf(true) }
        val (initialSize, initialPosition) = remember { loadWindowPrefs() }
        val windowState = rememberWindowState(size = initialSize, position = initialPosition)
        fun quit() {
            // Flush the geometry synchronously — a move/resize inside the debounce window would
            // otherwise be lost with the cancelled save (every exit path funnels through here).
            if (windowState.placement == WindowPlacement.Floating) {
                saveWindowPrefs(windowState.size, windowState.position)
            }
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
            // Held back until the graph is ready: an empty window that then repaints is a flash of
            // whatever the toolkit felt like showing. Composed all along, so paintWindowGround has
            // already coloured it by the time it appears.
            visible = windowVisible && (graph != null || bootError != null),
            onCloseRequest = {
                // A non-empty queue → the close button minimizes to the system tray and playback
                // carries on; an empty queue → quit.
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
            paintWindowGround(groundColorFor(darkChrome))

            LaunchedEffect(Unit) {
                try {
                    graph = buildGraph(settings)
                } catch (c: kotlinx.coroutines.CancellationException) {
                    throw c // the effect was disposed — not a boot failure
                } catch (t: Throwable) {
                    rootLogger.fork("boot").captureError(t)
                    bootError = t.message ?: t.toString()
                }
            }

            when {
                bootError != null -> Booting {
                    Text("Startup failed: $bootError", color = MaterialTheme.colorScheme.error)
                }
                // No spinner while the graph builds: the engines are already on disk by the time
                // this runs (the setup window is what waits for them), so the build is short and a
                // progress screen after a checklist that just said "Ready" reads as a second wait.
                // The window paints the app's own ground until the first frame has something in it.
                graph == null -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
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

private suspend fun buildGraph(settings: FileSettingsStore): AppGraph {
    val dataDir = ymusicDataDir().apply { mkdirs() }
    // File-backed jar for YouTube (engine cookie/get|set + player + proxy) and the YMusic API.
    // Desktop has no WebView cookie store; login-by-QR/paste comes later.
    val cookies = PersistentCookieJar(File(dataDir, "api-cookies.json").absolutePath.toPath(), FileSystem.SYSTEM)
    // One client stack, one disk cache. The engine HTTP client stays jar-less — JS cooks Cookie
    // headers against [cookies] — while the API client / media proxy use the jar directly.
    val http = AppHttpClients(File(dataDir, "http-cache").absolutePath.toPath(), logger = rootLogger)
    val deviceId = settings.anonymousDeviceId()

    // The one client of the YMusic service; null when this build has no service url.
    val api = REMOTE_CONFIG_URL.takeIf { it.isNotBlank() }?.let { baseUrl ->
        HttpApiService(
            client = http.engine.newBuilder().apply {
                cookieJar = cookies
            }.build(),
            os = desktopOsKey(),
            osVersion = System.getProperty("os.version") ?: "",
            appVersionCode = APP_VERSION_CODE,
            deviceId = deviceId,
            baseUrl = baseUrl,
            now = { System.currentTimeMillis() },
        )
    }
    // Flags, the newest JS bundle revisions, and update availability. Started here so the first
    // fetch overlaps the rest of startup; nothing below waits on it, and both JS seams read
    // whatever it holds at the moment they are asked (cached body on a cold start offline).
    remoteConfig = RemoteConfig(
        api = api,
        cacheFile = File(dataDir, "remote-config.json").absolutePath.toPath(),
        fileSystem = FileSystem.SYSTEM,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        now = { System.currentTimeMillis() },
        log = rootLogger.fork("remote-config"),
    ).also { it.checkUpdate() } // app open is itself a hotpoint
    // Anonymous usage stats — held by the graph so any screen can record events. The "Send
    // anonymous stats" answer is read per event, so the Settings switch applies live, and a
    // disabled gate costs nothing.
    val stats = StatUsage(
        api = api,
        enabled = { settings.flow(AppSettings.stats).value },
        queueFile = File(dataDir, "stat-queue.json").absolutePath.toPath(),
        fileSystem = FileSystem.SYSTEM,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        now = { System.currentTimeMillis() },
        log = rootLogger.fork("stats"),
    ).also { it.onAppStart() }

    // The engine bundle: jar-resource embed (no compiled-in constant) + file cache + remote
    // config (fetchConfig answers null until a config service exists). The manager boots
    // cached-or-embed and runs the periodic update check itself.
    val engineSource = DefaultJsSourceProvider(
        client = http.engine,
        config = { remoteConfig?.getConfig()?.engineJs?.let { it.url to it.version } },
        embedVersion = ENGINE_JS_VERSION,
        embedSource = { readAppResource("engine_ymusic.js") },
        fileSystem = FileSystem.SYSTEM,
        directory = File(dataDir, "engine-js").absolutePath.toPath(),
        now = { System.currentTimeMillis() },
        logger = rootLogger,
        ioDispatcher = Dispatchers.IO,
    )
    val engineCache = desktopEngineCacheStore(File(dataDir, "engine-cache"), rootLogger)
    val engines = withContext(Dispatchers.IO) {
        DynamicEngineManager(
            http.engine,
            appVersion = 1,
            logger = rootLogger,
            cacheStore = engineCache,
            cookieJar = cookies,
            settings = settings,
            strictStints = DEBUG_BUILD,
            // Every Sentry event names the engine-js bundle that was live (start + each swap).
            onBackendVersion = ::installSentryEngineVersion,
            jsSource = engineSource,
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
            resolveFfmpeg()?.let { mediaStoreArtFetcher(FfmpegMetadataGetter(it), mediaStore, imageNetworkFetcher) },
        ),
        scaledCache = DiskScaledImageCache(FileSystem.SYSTEM, File(dataDir, "image-scaled-cache").absolutePath.toPath()),
        logger = rootLogger,
        memoryCacheBytes = defaultMemoryCacheBudget(),
    )

    val localSurface = LocalVideoSurface()
    val mseSurface = io.ymusic.mse.MseVideoSurface()

    // Player cache: SABR miss is forwarded and teed to disk so re-plays/downloads reuse it.
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
            cookieJar = cookies
            defaultRedirectOptions = RedirectOptions(followRedirects = true, followSslRedirects = true)
        }
        LocalMediaProxy(
            playerCache, client,
            userAgent = DESKTOP_CHROME_UA,
            logger = rootLogger,
            // Chatty per-part/decision logs; -Dio.ymusic.player.verbose overrides the build default.
            verbose = System.getProperty("io.ymusic.player.verbose")?.toBoolean() ?: DEBUG_BUILD,
            // -Dymusic.player.serveSabrHits=false → fill-only, to test whether synthesized hits wedge switches.
            serveSabrHits = System.getProperty("io.ymusic.player.serveSabrHits", "true").toBoolean(),
        )
    }

    var ytEngine: io.ymusic.player.YtEngine? = null
    val player = withContext(Dispatchers.Main) {
        createPlayer(
            PlayerEngines(
                local = { createLocalPlayer(LocalPlayerContext(localSurface)) },
                webView = {
                    io.ymusic.player.mse.MsePlayer.create(
                        client = http.engine,
                        appVersion = 5000,
                        logger = rootLogger,
                        cacheStore = engineCache,
                        cookieJar = cookies,
                        settings = settings,
                        videoSurface = mseSurface,
                        main = engines,
                        mediaProxy = mediaProxy,
                    ).also { ytEngine = it }
                },
            ),
        )
    }

    // OS media surface — macOS Now Playing widget + media keys (Linux MPRIS / Windows SMTC have no
    // backend yet). Commands come back on the Main dispatcher, where the player is driven.
    connectMediaSession(player, CoroutineScope(Dispatchers.Main), rootLogger.fork("now-playing"))

    // Warm the YouTube MSE engine ~5s after startup so the first play skips JS/C construction.
    CoroutineScope(Dispatchers.Default).launch {
        delay(5_000)
        withContext(Dispatchers.Main) { player.prepareYoutubeEngine() }
    }

    // Offline downloads: SABR stack over ffmpeg. Spec comes from the MSE engine's cached
    // /player + decoded googlevideo URL (preload if that video is not already cached).
    val downloadService = withContext(Dispatchers.IO) {
        val ffmpegPath = resolveFfmpeg()
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
            cookieJar = cookies
            defaultRedirectOptions = RedirectOptions(followRedirects = false, followSslRedirects = false)
        }
        val resolver = io.ymusic.download.SabrSpecResolver { videoId ->
            withContext(Dispatchers.Main) { player.prepareYoutubeEngine() }
            val yt = ytEngine ?: error("youtube engine missing")
            WebViewSabrSpecResolver(yt).resolve(videoId)
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
            userAgent = DESKTOP_CHROME_UA,
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

    // Self-update: adopt anything a previous run staged, then poll. Disabled outright when no
    // manifest/key is configured — an unverifiable update is worse than none.
    val updateScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val updateService = UpdateService(
        client = http.engine,
        manifestUrl = UPDATE_MANIFEST_URL,
        publicKey = UPDATE_PUBLIC_KEY,
        currentVersionCode = APP_VERSION_CODE,
        scope = updateScope,
        logger = rootLogger,
        autoDownload = { autoDownloadUpdates },
    )
    // Mirrored into a field: the service asks synchronously, mid-check, and a suspending read
    // there would either block the check or race it.
    updateScope.launch {
        settings.flow(AppSettings.autoDownloadUpdates).collect { autoDownloadUpdates = it }
    }
    val desktopUpdates = if (updateService.isEnabled) {
        updateService.pruneSupersededVersions()
        updateService.adoptStagedUpdate()
        updateService.startPeriodicChecks()
        DesktopAppUpdates(updateService, updateScope)
    } else {
        null
    }

    // A folder picked during the first run is scanned here rather than in the setup window: the
    // scan is long, and the Library screen is where its progress and result belong.
    settings.flow(AppSettings.pendingLibraryFolder).value.takeIf { it.isNotBlank() }?.let { path ->
        CoroutineScope(Dispatchers.Default).launch {
            val uri = UriBuilder().scheme("file").authority("").path(path).build()
            runCatching { scanUris(mediaStore, listOf(uri)) {} }
                .onFailure { rootLogger.fork("library-scan").log(Logger.Level.WARN, "first-run scan of $path failed", it) }
            settings.put(AppSettings.pendingLibraryFolder, "")
        }
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
        // MSE blit and VLCJ frames scale; safe to display at art-tile size.
        surfaceScalesDown = true,
        loginBrowser = null,
        downloads = downloadService,
        updates = desktopUpdates,
        stats = stats,
        sendFeedback = sendFeedbackAction.takeIf { SENTRY_DSN.isNotEmpty() },
        pickFeedbackFiles = ::pickFeedbackFiles,
    ) { modifier ->
        DesktopPlayerSurface(localSurface, mseSurface, modifier)
    }
}

@Composable
private fun DesktopPlayerSurface(
    localSurface: LocalVideoSurface,
    mseSurface: io.ymusic.mse.MseVideoSurface,
    modifier: Modifier,
) {
    val localVisible by localSurface.visible.collectAsState()
    val localFrame by localSurface.frame.collectAsState()
    var size by remember { mutableStateOf(IntSize.Zero) }
    val localBitmap = remember(localFrame) { localFrame?.toComposeImageBitmap() }
    val msePanel = remember { MseVideoPanel() }
    DisposableEffect(mseSurface, msePanel) {
        msePanel.onPainted = { mseSurface.swingShown = it }
        val blit: (java.awt.image.BufferedImage?) -> Unit = { msePanel.setImage(it) }
        mseSurface.swingBlit = blit
        onDispose {
            msePanel.onPainted = null
            if (mseSurface.swingBlit === blit) mseSurface.swingBlit = null
        }
    }
    Box(
        modifier.onSizeChanged {
            size = it
            if (it.width > 0 && it.height > 0) {
                mseSurface.setBlitSize(it.width, it.height)
            }
        },
    ) {
        if (localVisible) {
            Box(Modifier.fillMaxSize().background(Color.Black))
            if (localBitmap != null) {
                Image(
                    bitmap = localBitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else {
            // Same blit as playground MseNativeVideo: Swing paints BGRA, Compose does not recompose
            // each frame. contains()=false leaves caption/menu clicks in Compose.
            SwingPanel(
                background = Color.Black,
                factory = { msePanel },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Playground-identical MSE hole: Swing draws the latest BGRA frame. [contains] always misses so
 * Compose's interop filter does not swallow clicks over the video.
 */
private class MseVideoPanel : JPanel() {
    @Volatile private var img: BufferedImage? = null

    @Volatile var onPainted: ((BufferedImage?) -> Unit)? = null
    private val pending = java.util.concurrent.atomic.AtomicBoolean(false)

    init {
        isOpaque = true
        background = AwtColor.BLACK
        isFocusable = false
        isEnabled = false
        isRequestFocusEnabled = false
    }

    override fun contains(x: Int, y: Int): Boolean = false

    fun setImage(image: BufferedImage?) {
        img = image
        if (pending.compareAndSet(false, true)) {
            SwingUtilities.invokeLater {
                pending.set(false)
                repaint()
            }
        }
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g as Graphics2D
        g2.color = AwtColor.BLACK
        g2.fillRect(0, 0, width, height)
        val image = img ?: return
        onPainted?.invoke(image)
        val scale = min(width / image.width.toDouble(), height / image.height.toDouble())
        val dw = (image.width * scale).toInt().coerceAtLeast(1)
        val dh = (image.height * scale).toInt().coerceAtLeast(1)
        val x = (width - dw) / 2
        val y = (height - dh) / 2
        if (dw == image.width && dh == image.height) {
            g2.drawImage(image, x, y, null)
        } else {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g2.drawImage(image, x, y, dw, dh, null)
        }
    }
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
    val ffmpeg = resolveFfmpeg()
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
/**
 * Everything the app persists lives here: engine + image caches, the media store, downloads,
 * cookies, window geometry. `$YMUSIC_DATA_DIR` relocates the lot — a packaged build's launcher
 * passes neither JVM args nor JAVA_TOOL_OPTIONS through, so an env var is the only way to run a
 * second copy (testing a release build while the dev one is open) or to keep state on an external
 * disk.
 */
internal fun ymusicDataDir(): File =
    System.getenv("YMUSIC_DATA_DIR")?.takeIf { it.isNotBlank() }?.let(::File)
        ?: File(System.getProperty("user.home"), ".ymusic")

private val windowPrefsFile = File(ymusicDataDir(), "window.properties")
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

/**
 * The live remote config, once [buildGraph] has built it.
 *
 * Held at file scope because the two JS source providers are constructed with a lambda that must
 * read whatever the CURRENT config says at the moment it is asked, not a snapshot taken while the
 * graph was still being assembled.
 */
internal var remoteConfig: RemoteConfig? = null
    private set

/** The coarse platform name the config service keys releases by. */
private fun desktopOsKey(): String {
    val os = System.getProperty("os.name").lowercase()
    return when {
        os.contains("mac") || os.contains("darwin") -> "macos"
        os.contains("win") -> "windows"
        else -> "linux"
    }
}

/**
 * The ffmpeg this run should use, or null when the host has none.
 *
 * One ladder for every caller (tag reading, artwork, download muxing) so they can never disagree
 * about which binary is in play: an explicit override, then the copy the first run downloaded
 * ([io.ymusic.desktop.setup.FfmpegProvisioner]), then the PATH.
 *
 * A dev run takes the first rung: `:desktopApp:run` and the tests are given -Dio.ymusic.ffmpeg
 * pointing at the prebuilt from the latest ffmpeg-build release, so they never wait on the
 * first-run download. A shipped build has no such property and provisions one.
 */
internal fun resolveFfmpeg(): String? {
    System.getProperty("io.ymusic.ffmpeg")?.let { return File(it).takeIf { f -> f.canExecute() }?.absolutePath }
    System.getenv("YMUSIC_FFMPEG")?.let { if (File(it).canExecute()) return it }
    provisionedFfmpeg()?.let { return it.absolutePath }
    val os = System.getProperty("os.name").lowercase()
    val name = if (os.contains("win")) "ffmpeg.exe" else "ffmpeg"
    val dirs = System.getenv("PATH").orEmpty().split(File.pathSeparator) +
        listOf("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin")
    return dirs.asSequence().map { File(it, name) }.firstOrNull { it.canExecute() }?.absolutePath
}

/** Newest provisioned version first — an upgrade wins without the old copy having to be deleted. */
private fun provisionedFfmpeg(): File? {
    val name = if (System.getProperty("os.name").lowercase().contains("win")) "ffmpeg.exe" else "ffmpeg"
    return File(ymusicDataDir(), "tools/ffmpeg").listFiles().orEmpty()
        .filter { it.isDirectory }
        .sortedByDescending { it.name }
        .map { File(it, name) }
        .firstOrNull { it.canExecute() }
}

/**
 * Match the OS window decorations — title bar, traffic lights — to the theme the app will render in.
 *
 * macOS reads this once when AWT starts its toolkit, so it must be set before any window exists, and
 * a theme change in Settings only reaches the title bar on the next launch. The value is an explicit
 * appearance rather than "system": the app's theme is its own setting, and a dark app under a light
 * OS should not get a white title bar.
 *
 * Windows would need DwmSetWindowAttribute per window and Linux is at the window manager's
 * discretion; neither is wired up, so both keep the system default for now.
 */
private fun applyWindowChrome(dark: Boolean) {
    // A JFrame takes its background from UIManager's "control" colour AT CONSTRUCTION — before any
    // composable, effect or window property of ours can run. That is the whole launch flash: the
    // frame is built white and Compose only covers it when it first draws. Setting the colour here,
    // before the toolkit builds anything, is the only place early enough.
    // https://github.com/JetBrains/compose-multiplatform/issues/1794
    val ground = java.awt.Color(groundColorFor(dark).toArgb(), true)
    // "control" alone is not enough: it colours the frame and its root pane, but the content pane
    // inside them is an opaque JPanel that takes Aqua's own system colour (a SystemColorProxy at
    // #EEEEEE) and stays white. That panel is what actually flashes, so every key the Swing window
    // is assembled from has to be set, not just the one the issue names.
    listOf("control", "Panel.background", "RootPane.background", "Viewport.background")
        .forEach { javax.swing.UIManager.put(it, ground) }

    if (!System.getProperty("os.name").lowercase().contains("mac")) return
    System.setProperty(
        "apple.awt.application.appearance",
        if (dark) "NSAppearanceNameDarkAqua" else "NSAppearanceNameAqua",
    )
}

/** Opens a link in the user's browser — the first run's VLC, GitHub and policy links. */
internal fun openInBrowser(url: String) {
    runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }
        .onFailure { rootLogger.fork("browser").log(Logger.Level.WARN, "could not open $url", it) }
}

private const val DESKTOP_CHROME_UA =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36"

/** A file packed into the app jar (the staged engine bundle + version — see stageEngineJs). */
private fun readAppResource(name: String): String =
    checkNotNull(object {}.javaClass.getResourceAsStream("/" + name)) { name + " missing from app resources" }
        .readBytes().decodeToString()

@Composable
private fun Booting(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}
