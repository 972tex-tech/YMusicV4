package io.ymusic.android

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Intent
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import io.sentry.kotlin.multiplatform.Sentry
import io.ymusic.download.DownloadChange
import io.ymusic.dynamicengine.CacheStore
import io.ymusic.dynamicengine.DefaultJsSourceProvider
import io.ymusic.dynamicengine.DynamicEngineManager
import io.ymusic.dynamicengine.ENGINE_BUNDLE
import io.ymusic.dynamicengine.JsSourceProvider
import io.ymusic.dynamicengine.SignedBundleRef
import io.ymusic.dynamicengine.androidEngineCacheStore
import io.ymusic.dynamicengine.player.bindMseNativeLogs
import io.ymusic.dynamicengine.playerJsSource
import io.ymusic.imageloader.DiskScaledImageCache
import io.ymusic.imageloader.DiskVariantIndex
import io.ymusic.imageloader.ImageLoader
import io.ymusic.imageloader.ImageSourceCache
import io.ymusic.imageloader.defaultMemoryCacheBudget
import io.ymusic.imageloader.fileFetcher
import io.ymusic.imageloader.networkFetcher
import io.ymusic.mediascanner.DocumentInfo
import io.ymusic.mediascanner.FfmpegMetadataExtractor
import io.ymusic.mediascanner.MediaScanner
import io.ymusic.mediascanner.SafDocumentsProvider
import io.ymusic.mediastore.BetterMediaStore
import io.ymusic.mediastore.mediaStoreAndroidDriver
import io.ymusic.mediastore.model.BucketDraft
import io.ymusic.mse.MseVideoSurface
import io.ymusic.player.LocalPlayerContext
import io.ymusic.player.PlayerEngines
import io.ymusic.player.createLocalPlayer
import io.ymusic.player.createPlayer
import io.ymusic.player.isPlaying
import io.ymusic.player.mse.MsePlayer
import io.ymusic.remoteconfig.ApiService
import io.ymusic.remoteconfig.HttpApiService
import io.ymusic.remoteconfig.RemoteConfig
import io.ymusic.remoteconfig.StatUsage
import io.ymusic.settings.AppSettings
import io.ymusic.settings.anonymousDeviceId
import io.ymusic.settings.flow
import io.ymusic.telemetry.SentryLogger
import io.ymusic.telemetry.applyYMusicTelemetry
import io.ymusic.telemetry.bindCrashReporting
import io.ymusic.telemetry.installSentryDeviceId
import io.ymusic.telemetry.installSentryEngineVersion
import io.ymusic.telemetry.sendSentryFeedback
import io.ymusic.ui.APP_HTTP_TIMEOUT
import io.ymusic.ui.AppGraph
import io.ymusic.ui.AppHttpClients
import io.ymusic.ui.WebViewSabrSpecResolver
import io.ymusic.ui.downloadLocationFolder
import io.ymusic.ui.ensureAppLanguage
import io.ymusic.ui.loaderYoutubeArt
import io.ymusic.ui.mediaStoreArtFetcher
import io.ymusic.ui.playhistory.PlayHistoryStore
import io.ymusic.ui.playhistory.playHistoryAndroidDriver
import io.ymusic.ui.registerCompletedDownload
import io.ymusic.ui.sponsorblock.SqliteSponsorBlockStore
import io.ymusic.ui.sponsorblock.sponsorBlockAndroidDriver
import io.ymusic.ytmedia.cache.DiskMediaBlobCache
import io.ymusic.ytmedia.cache.MediaCache
import io.ymusic.ytmedia.cache.SqlDelightBlobIndex
import io.ymusic.ytmedia.cache.SqlDelightCacheStats
import io.ymusic.ytmedia.cache.SqlDelightSegmentIndex
import io.ymusic.ytmedia.cache.androidSegmentDatabaseDriver
import io.ymusic.ytmedia.proxy.LocalMediaProxy
import kmpcommon.log.Logger
import kmpcommon.log.TeeLogger
import kmpcommon.uri.Uri
import kmpffmpeg.AndroidFfInputResolver
import kmpffmpeg.metadata.FfmpegMetadataGetter
import kmphttp.KmpHttpClient
import kmphttp.RedirectOptions
import kmphttp.create
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath
import projectcommon.log.FileLogger
import projectcommon.log.LoggingWorker
import java.io.File

/**
 * The PROCESS-scoped half of the app graph: everything that must exist exactly once no matter how
 * many times the Activity is recreated. AppHttpClients is the load-bearing member — a second
 * instance would open a second okhttp Cache over the same directory, which corrupts it (see its
 * KDoc) — but the engine manager, media store, settings and image loader equally have no business
 * dying with a rotation.
 */
class SharedServices(
    val http: AppHttpClients,
    val cookies: WebViewCookieJar,
    val settings: PrefsSettingsStore,
    val engines: DynamicEngineManager,
    val cacheStore: CacheStore,
    val mediaStore: BetterMediaStore,
    /** Bundled `webview_ymusic.js` (player-cache gate). Null disables [LocalMediaProxy]. */
    val webViewJs: String?,
    /** The MSE player bundle, upgraded between player sessions. */
    val playerJs: JsSourceProvider,
    val imageLoader: ImageLoader,
    val stats: StatUsage,
    val updates: AndroidAppUpdates,
    /** The YMusic service; null when this build has no service url. */
    val api: ApiService?,
)

class YMusicApplication : Application() {

    /** Rotating file sink (`filesDir/logs/ymusic.log`, 5 MiB × 3). Lazy: needs [filesDir]. */
    val logWorker: LoggingWorker by lazy { LoggingWorker(File(filesDir, "logs").toOkioPath()) }

    /** The app's root logger: file + Sentry (no-op until Sentry.init) teed with logcat. */
    val rootLogger: Logger by lazy {
        TeeLogger(FileLogger(logWorker), TeeLogger(SentryLogger(), LogcatLogger()))
            .also { it.bindMseNativeLogs() }
    }

    /** Escaped coroutine failures are still one job dying — capture, don't crash the process. */
    private fun uncaught(scopeName: String) = CoroutineExceptionHandler { _, e ->
        rootLogger.fork(scopeName).fork("uncaught").captureError(e)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + uncaught("app-default"))

    /** Main-thread, app-lifetime scope for playback glue (service start, audio focus). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + uncaught("app-main"))

    /** The current Activity's SAF folder pick, registered while it lives. The graph is app-scoped
     *  but a pick needs an Activity; a scan is always user-driven, so one is up when this runs. */
    @Volatile var folderPicker: (suspend () -> Uri?)? = null

    /** The current Activity's feedback-attachment file pick — same lifecycle as [folderPicker]. */
    @Volatile var feedbackFilePicker: (suspend () -> List<io.ymusic.ui.FeedbackFile>)? = null

    /** The current Activity's QR scan — same lifecycle as [folderPicker]. */
    @Volatile var qrScanner: (suspend () -> String?)? = null

    /** The current Activity's PIN / biometric prompt — same lifecycle as [folderPicker]. */
    @Volatile var deviceOwnerAuth: (suspend (String, String) -> Boolean)? = null

    // For onTrimMemory, which cannot await the deferred; set once the build completes.
    @Volatile private var builtImageLoader: ImageLoader? = null

    /** Built off-main on FIRST await, not in onCreate — the first CookieManager.getInstance()
     *  alone initializes the whole WebView provider, which froze a slow device for seconds. */
    val services: Deferred<SharedServices> by lazy { scope.async { buildServices() } }

    private var builtGraph: Deferred<AppGraph>? = null

    /** Single-flight graph build over [services]; call from the main thread. Playback must outlive
     *  the Activity (the [PlaybackService] mirrors the player into a MediaSession after the UI is
     *  gone), so the player and its video container live here, against the application context. */
    fun graphAsync(): Deferred<AppGraph> =
        builtGraph ?: appScope.async { buildGraph() }.also { builtGraph = it }

    private suspend fun buildServices(): SharedServices {
        // YouTube + API cookies live in the WebView CookieManager (the login WebView writes
        // there; this jar is a wrapper so HTTP sees the same store).
        val cookies = WebViewCookieJar()
        // One client stack, one disk cache, shared by the engine and the player's embed fetch.
        val http = AppHttpClients(cacheDir.resolve("http-cache").toOkioPath(), logger = rootLogger)
        val settings = PrefsSettingsStore.open(this)
        val deviceId = settings.anonymousDeviceId()
        // Crash reporting is consent-gated and LIVE: init runs once (crash handlers install and
        // stay), but nothing leaves the process unless the settings toggle says yes — default
        // off, so nothing is ever sent before the user opted in.
        if (BuildConfig.SENTRY_DSN.isNotEmpty()) {
            bindCrashReporting(appScope, settings.flow(AppSettings.crashReports)) {
                Sentry.init {
                    it.applyYMusicTelemetry()
                    it.dsn = BuildConfig.SENTRY_DSN
                    // The commit in the release traces an event to git history (no source upload).
                    it.release = "${BuildConfig.APPLICATION_ID}@${BuildConfig.VERSION_NAME}+${BuildConfig.GIT_COMMIT}"
                    it.environment = if (BuildConfig.DEBUG) "debug" else "production"
                }
                installSentryDeviceId(deviceId)
            }
        }

        // The one client of the YMusic service; null when this build has no service url. Its own
        // client instance carries the API cookie jar (the activity watermark survives restarts) —
        // the engine client stays jar-less on purpose, the engine JS owns YouTube cookies.
        val api = BuildConfig.REMOTE_CONFIG_URL.takeIf { it.isNotBlank() }?.let { baseUrl ->
            HttpApiService(
                client = http.engine.newBuilder().apply {
                    cookieJar = cookies
                }.build(),
                os = "android",
                osVersion = android.os.Build.VERSION.RELEASE ?: "",
                appVersionCode = BuildConfig.VERSION_CODE,
                deviceId = deviceId,
                baseUrl = baseUrl,
                now = { System.currentTimeMillis() },
                // The device's preferred ABI, so the service can offer its split APK.
                arch = android.os.Build.SUPPORTED_ABIS.firstOrNull(),
            )
        }
        // Flags, the newest JS bundle revisions, and update availability. Started here so the first
        // fetch overlaps the rest of startup; nothing below waits on it, and both JS seams read
        // whatever it holds when they are asked (the cached body on a cold start offline).
        val remoteConfig = RemoteConfig(
            api = api,
            cacheFile = File(filesDir, "remote-config.json").toOkioPath(),
            fileSystem = FileSystem.SYSTEM,
            scope = appScope,
            now = { System.currentTimeMillis() },
            log = rootLogger.fork("remote-config"),
        ).also { it.checkUpdate() } // app open is itself a hotpoint
        // Anonymous usage stats — held by SharedServices so the graph (and any screen) can
        // record events. The "Send anonymous stats" answer is read per event, so the Settings
        // switch applies live, and a disabled gate costs nothing.
        val stats = StatUsage(
            api = api,
            enabled = { settings.flow(AppSettings.stats).value },
            queueFile = File(filesDir, "stat-queue.json").toOkioPath(),
            fileSystem = FileSystem.SYSTEM,
            scope = scope,
            now = { System.currentTimeMillis() },
            log = rootLogger.fork("stats"),
        ).also { it.onAppStart() }
        // First launch decides the app language (device if supported, else English) and persists
        // it — BEFORE the engine starts: the engine pulls the same key as its content-language.
        ensureAppLanguage(settings)
        // The engine bundle: asset embed (no compiled-in constant) + file cache + the revision the
        // config service publishes. The manager boots cached-or-embed and runs the periodic update
        // check itself.
        val engineSource = DefaultJsSourceProvider(
            client = http.engine,
            config = { remoteConfig.getConfig().engineJs?.let { SignedBundleRef(it.version, it.url, it.signature) } },
            name = ENGINE_BUNDLE,
            publicKey = BuildConfig.BUNDLE_PUBLIC_KEY,
            embedVersion = BuildConfig.ENGINE_JS_VERSION,
            embedSource = { assets.open("engine_ymusic.js").readBytes().decodeToString() },
            fileSystem = FileSystem.SYSTEM,
            directory = File(filesDir, "engine-js").toOkioPath(),
            now = { System.currentTimeMillis() },
            logger = rootLogger,
            ioDispatcher = Dispatchers.IO,
        )
        val cacheStore = androidEngineCacheStore(this, logger = rootLogger)
        val engines = DynamicEngineManager(
            http.engine,
            appVersion = 1,
            logger = rootLogger,
            cacheStore = cacheStore,
            cookieJar = cookies,
            settings = settings,
            // Debug builds crash on an engine-thread stall (StrictMode-style); release only logs.
            strictStints = BuildConfig.DEBUG,
            // Every Sentry event names the engine-js bundle that was live (start + each swap).
            onBackendVersion = ::installSentryEngineVersion,
            jsSource = engineSource,
        )
        engines.start()

        val mediaStore = BetterMediaStore.create(mediaStoreAndroidDriver(this))

        // The player-cache script ships in the APK and is not remotely updated. A missing asset
        // silently disables the whole player-cache path — a packaging bug, not a soft toggle.
        val webViewJs = runCatching { assets.open("webview_ymusic.js").readBytes().decodeToString() }
            .onFailure { rootLogger.fork("boot").captureError(it, "webview_ymusic.js asset unreadable — player cache disabled") }
            .getOrNull()

        // Thumbnails: everything is a fetcher — http on a cache-less client (originals stream to
        // the fetcher's disk cache, decode from file) + local files + art:// embedded covers via
        // the bundled ffmpeg (absent binary keeps art:// unresolved → placeholders). Memory budget
        // scales with the device's heap class; onTrimMemory (below) sheds it under pressure.
        val ffmpegBin = File(applicationInfo.nativeLibraryDir, "libffmpeg.so")
            .takeIf { it.exists() }?.absolutePath
        val imageNetworkFetcher = networkFetcher(
            http.image,
            FileSystem.SYSTEM,
            cacheDir.resolve("image-source-cache").toOkioPath(),
        )
        var loaderRef: ImageLoader? = null
        val imageLoader = ImageLoader(
            fetchers = listOfNotNull(
                imageNetworkFetcher,
                fileFetcher(),
                ffmpegBin?.let {
                    mediaStoreArtFetcher(
                        FfmpegMetadataGetter(it, AndroidFfInputResolver(contentResolver)),
                        mediaStore,
                        loaderYoutubeArt { checkNotNull(loaderRef) },
                        coverCache = ImageSourceCache(FileSystem.SYSTEM, cacheDir.resolve("image-cover-cache").toOkioPath(), 32L * 1024 * 1024),
                    )
                },
            ),
            scaledCache = DiskScaledImageCache(FileSystem.SYSTEM, cacheDir.resolve("image-scaled-cache").toOkioPath()),
            logger = rootLogger,
            memoryCacheBytes = defaultMemoryCacheBudget(this),
            variantIndex = DiskVariantIndex(FileSystem.SYSTEM, cacheDir.resolve("image-variants").toOkioPath()),
        ).also {
            loaderRef = it
            builtImageLoader = it
        }

        val playerJs = playerJsSource(
            client = http.engine,
            config = { remoteConfig.getConfig().playerJs?.let { SignedBundleRef(it.version, it.url, it.signature) } },
            publicKey = BuildConfig.BUNDLE_PUBLIC_KEY,
            fileSystem = FileSystem.SYSTEM,
            directory = File(filesDir, "player-js").toOkioPath(),
            now = { System.currentTimeMillis() },
            logger = rootLogger,
            ioDispatcher = Dispatchers.IO,
        )
        // In-app updates from the config's appUpdate. The autoDownload read is synchronous, mid-offer.
        val updates = AndroidAppUpdates(
            context = this,
            client = http.engine,
            currentVersionCode = BuildConfig.VERSION_CODE,
            scope = scope,
            logger = rootLogger,
            autoDownload = { settings.flow(AppSettings.autoDownloadUpdates).value },
        )
        updates.pruneDownloads()
        scope.launch { remoteConfig.config.collect { updates.offer(it.appUpdate) } }
        return SharedServices(http, cookies, settings, engines, cacheStore, mediaStore, webViewJs, playerJs, imageLoader, stats, updates, api)
    }

    private suspend fun buildGraph(): AppGraph {
        // One shared video host (a FrameLayout): the local engine adds its SurfaceView, MSE paints
        // into a TextureView, and a hidden WebView cooks download specs. Views must be born on the
        // main thread; everything else builds off it (see [services]).
        val videoContainer = FrameLayout(this)
        val downloadContainer = FrameLayout(this).apply { visibility = View.GONE }
        videoContainer.addView(downloadContainer, FrameLayout.LayoutParams(1, 1))
        val mseTexture = TextureView(this).apply { isOpaque = true }
        videoContainer.addView(
            mseTexture,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        mseTexture.visibility = View.GONE
        val mseBridge = MseSurfaceBridge(mseTexture)
        mseTexture.surfaceTextureListener = mseBridge.listener
        val graph = withContext(Dispatchers.Default) {
            buildGraphOffMain(videoContainer, downloadContainer, mseTexture, mseBridge)
        }
        // The session/notification host must be up whenever playback is — every playing transition
        // (re)starts it. Playback only ever starts from a user action with the app foregrounded, so
        // the FGS-start restriction doesn't bite; if it ever does, media3 notification actions and
        // media buttons can still revive the service.
        appScope.launch {
            combine(graph.player.status, graph.player.queue) { status, queue -> isPlaying(status, queue) }
                .distinctUntilChanged()
                .filter { it }
                .collect {
                    try {
                        ContextCompat.startForegroundService(
                            this@YMusicApplication,
                            Intent(this@YMusicApplication, PlaybackService::class.java),
                        )
                    } catch (e: IllegalStateException) {
                        rootLogger.fork("playback-service").log(Logger.Level.WARN, "foreground start rejected", e)
                    }
                }
        }
        AudioFocusCoordinator(this, graph.player, appScope).start()
        // Same pattern for downloads: any change that (re)starts work also (re)starts the
        // dataSync foreground host; the service winds itself down when the queue drains. Enqueue
        // and resume are user actions with the app foregrounded, so FGS-start is permitted.
        graph.downloads?.let { downloads ->
            appScope.launch {
                downloads.changes.collect { change ->
                    val startsWork = change is DownloadChange.Added ||
                        (
                            change is DownloadChange.StateChanged &&
                                change.state.isActive
                            )
                    if (startsWork) {
                        try {
                            ContextCompat.startForegroundService(
                                this@YMusicApplication,
                                Intent(this@YMusicApplication, DownloadForegroundService::class.java),
                            )
                        } catch (e: IllegalStateException) {
                            rootLogger.fork("download-service").log(Logger.Level.WARN, "foreground start rejected", e)
                        }
                    }
                }
            }
        }
        return graph
    }

    private suspend fun buildGraphOffMain(
        videoContainer: FrameLayout,
        downloadContainer: FrameLayout,
        mseTexture: TextureView,
        mseBridge: MseSurfaceBridge,
    ): AppGraph {
        val shared = services.await()

        // Player cache: the WebView bridge intercepts the bundle's /ymusicForward (rewritten from
        // /videoplayback) and serves it from this router — hit → synthesized bytes, miss → forwarded and
        // teed to disk. Its client carries the persistent jar (shared.cookies) + the WebView UA.
        // The player cache; shared read-only with the download stack so a download reuses segments already
        // cached from playback (never writing into it).
        val playerCache = run {
            val cacheRoot = File(applicationContext.cacheDir, "player-cache").apply { mkdirs() }
            val driver = androidSegmentDatabaseDriver(applicationContext) // shared: span index + segment index
            MediaCache(
                DiskMediaBlobCache(cacheRoot.toOkioPath(), FileSystem.SYSTEM, SqlDelightBlobIndex(driver, now = { System.currentTimeMillis() })),
                SqlDelightSegmentIndex(driver, now = { System.currentTimeMillis() }),
                SqlDelightCacheStats(driver),
            )
        }
        val mediaProxy = shared.webViewJs?.let {
            val client = KmpHttpClient.create {
                defaultExecuteTimeout = APP_HTTP_TIMEOUT
                logger = rootLogger.fork("kmphttp")
                cookieJar = shared.cookies
                defaultRedirectOptions = RedirectOptions(followRedirects = true, followSslRedirects = true)
            }
            LocalMediaProxy(
                playerCache,
                client,
                userAgent = WebSettings.getDefaultUserAgent(applicationContext),
                logger = rootLogger,
                // Per-request media-path logging — debug builds only, it narrates the hot path.
                verbose = BuildConfig.DEBUG,
            )
        }

        var ytEngine: io.ymusic.player.YtEngine? = null
        var msePlayer: MsePlayer? = null
        val player = createPlayer(
            PlayerEngines(
                local = { createLocalPlayer(LocalPlayerContext(this, videoContainer)) },
                youtube = {
                    MsePlayer.create(
                        client = shared.http.engine,
                        appVersion = 5000,
                        logger = rootLogger,
                        cacheStore = shared.cacheStore,
                        cookieJar = shared.cookies,
                        settings = shared.settings,
                        videoSurface = MseVideoSurface(),
                        main = shared.engines,
                        mediaProxy = mediaProxy,
                        playerJs = shared.playerJs,
                    ).also {
                        ytEngine = it
                        msePlayer = it
                        mseBridge.attach(it)
                    }
                },
            ),
        )

        // Offline downloads: SABR stack + on-device ffmpeg. Spec from MSE /player + decoded SABR URL.
        val downloadService = run {
            val ffmpegBin = File(applicationInfo.nativeLibraryDir, "libffmpeg.so")
                .takeIf { f -> f.exists() }?.absolutePath
            // Probes a finished download's metadata into its Library bucket (same path as the folder scan,
            // and it reads content:// via SAF); null without ffmpeg → a hand-built draft fallback.
            val downloadScanner = ffmpegBin?.let {
                MediaScanner.create(
                    shared.mediaStore,
                    SafDocumentsProvider(applicationContext),
                    FfmpegMetadataExtractor(FfmpegMetadataGetter(it, AndroidFfInputResolver(contentResolver))),
                )
            }
            val sabrClient = KmpHttpClient.create {
                defaultExecuteTimeout = APP_HTTP_TIMEOUT
                logger = rootLogger.fork("kmphttp")
                cookieJar = shared.cookies
                defaultRedirectOptions = RedirectOptions(followRedirects = false, followSslRedirects = false)
            }
            io.ymusic.download.DownloadService(
                store = io.ymusic.download.DownloadStore(io.ymusic.download.downloadDatabaseAndroidDriver(applicationContext), rootLogger),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + uncaught("downloads")),
                now = { System.currentTimeMillis() },
                resolver = io.ymusic.download.SabrSpecResolver { videoId ->
                    withContext(Dispatchers.Main) { player.prepareYoutubeEngine() }
                    val yt = ytEngine ?: error("youtube engine missing")
                    WebViewSabrSpecResolver(yt).resolve(videoId)
                },
                client = sabrClient,
                userAgent = WebSettings.getDefaultUserAgent(applicationContext),
                ffmpeg = ffmpegBin?.let { bin -> io.ymusic.download.Ffmpeg(bin) },
                // Handles file:// (app dirs) via okio and user-picked SAF folders (content://) via SAF.
                storage = AndroidDownloadStorage(
                    applicationContext,
                    FileSystem.SYSTEM,
                    File(cacheDir, "download-work").absolutePath.toPath(),
                ),
                mediaCache = playerCache, // read-only (see YtStreamDownloader): reuse cached playback bytes
                // Own segment index for SABR resume (byte range per segment), separate from the player cache DB.
                segmentIndex = SqlDelightSegmentIndex(
                    androidSegmentDatabaseDriver(applicationContext, name = "download_segments.db"),
                    now = { System.currentTimeMillis() },
                ),
                // The user's chosen download location (SAF content:// or file://) — always set: the download
                // flow requires picking one before enqueue.
                downloadFolder = { shared.settings.downloadLocationFolder()!! },
                onCompleted = { task, result ->
                    registerCompletedDownload(shared.mediaStore, shared.settings, downloadScanner, task, result)
                },
                logger = rootLogger,
                playerResponse = { videoId ->
                    withContext(Dispatchers.Main) { player.prepareYoutubeEngine() }
                    val yt = ytEngine ?: error("youtube engine missing")
                    WebViewSabrSpecResolver(yt).playerResponse(videoId)
                },
            )
        }

        val versionName = packageManager.getPackageInfo(packageName, 0).versionName
        return AppGraph(
            shared.engines,
            player,
            logger = rootLogger,
            playerCache = playerCache,
            stats = shared.stats,
            updates = shared.updates,
            api = shared.api,
            mediaStore = shared.mediaStore,
            playHistory = PlayHistoryStore(playHistoryAndroidDriver(this)),
            sponsorBlockStore = SqliteSponsorBlockStore(sponsorBlockAndroidDriver(this)),
            scanLibraryFolder = { onProgress -> scanPickedFolder(shared.mediaStore, onProgress) },
            libraryFolderPicker = { pickAndRegisterFolder(shared.mediaStore) },
            rescanLibrary = { folderUri, onProgress -> rescanKnownRoots(shared.mediaStore, folderUri, onProgress) },
            deleteFromDisk = { item ->
                withContext(Dispatchers.IO) {
                    val uri = android.net.Uri.parse(item.uri.toString())
                    // Rides the persisted tree permission from the folder pick. An already-gone
                    // document still clears its library row (matching the desktop shell).
                    val deleted = try {
                        android.provider.DocumentsContract.deleteDocument(contentResolver, uri)
                    } catch (e: java.io.FileNotFoundException) {
                        true
                    }
                    check(deleted) { "Android refused to delete this document" }
                }
            },
            appVersion = listOfNotNull(versionName, android.os.Build.SUPPORTED_ABIS.firstOrNull()).joinToString(" · "),
            imageLoader = shared.imageLoader,
            settings = shared.settings,
            loginBrowser = { url, state, onPageChanged, modifier ->
                AndroidLoginBrowser(url, state, onPageChanged, modifier)
            },
            exportSessionCookies = { shared.cookies.exportSessionCookies() },
            scanQrCode = { qrScanner?.invoke() },
            confirmDeviceOwner = { title, subtitle ->
                val fn = deviceOwnerAuth ?: error("no-lock")
                fn(title, subtitle)
            },
            downloads = downloadService,
            sendFeedback = sendFeedbackAction.takeIf { BuildConfig.SENTRY_DSN.isNotEmpty() },
            pickFeedbackFiles = { feedbackFilePicker?.invoke() ?: emptyList() },
        ) { modifier ->
            // The ONE shared videoContainer is hosted at two call sites (the docked preview and Now
            // Playing). They're logically exclusive, but across a transition frame Compose builds the
            // new host before disposing the old one — and Android's addView does NOT auto-detach
            // (unlike Swing/UIKit), so adding the container directly throws "child already has a
            // parent". So each AndroidView gets its own empty host and re-parents the shared container
            // into whichever is active, detaching from the previous parent first.
            val status by player.status.collectAsState()
            AndroidView(
                factory = { context -> FrameLayout(context) },
                modifier = modifier.fillMaxSize(),
                update = { host ->
                    if (videoContainer.parent !== host) {
                        (videoContainer.parent as? ViewGroup)?.removeView(videoContainer)
                        host.addView(videoContainer)
                        mseBridge.reattach(mseTexture)
                    }
                    val showMse = status.videoSize != null
                    mseTexture.visibility = if (showMse) View.VISIBLE else View.GONE
                    if (showMse) mseTexture.bringToFront()
                },
            )
        }
    }

    /** Delivers a feedback report as Sentry user feedback; attachments ride the anchor event. */
    private val sendFeedbackAction: suspend (io.ymusic.ui.FeedbackDraft) -> Unit = { draft ->
        withContext(Dispatchers.IO) {
            runCatching { logWorker.flush() }
                .onFailure { rootLogger.fork("feedback").log(Logger.Level.WARN, "log flush before feedback failed", it) }
            val logs = logWorker.filePaths().map { path ->
                val name = path.substringAfterLast('/').substringAfterLast('\\')
                io.sentry.kotlin.multiplatform.Attachment(path, name, "text/plain")
            }
            sendSentryFeedback(
                comments = draft.message,
                name = draft.name,
                email = draft.email,
                attachments = logs + draft.attachments.mapNotNull { f ->
                    f.bytes?.let { b ->
                        f.contentType?.let { type -> io.sentry.kotlin.multiplatform.Attachment(b, f.filename, type) }
                            ?: io.sentry.kotlin.multiplatform.Attachment(b, f.filename)
                    }
                },
            )
        }
    }

    /** SAF folder pick + full scan into [store]; returns the summary line, or null when canceled. */
    private suspend fun scanPickedFolder(store: BetterMediaStore, onProgress: (Int) -> Unit): String? {
        val treeUri = folderPicker?.invoke() ?: return null
        return scanUris(store, listOf(treeUri), onProgress)
    }

    /** Pick a folder and register it as a Library bucket (no media scan — works even when empty),
     *  returning its uri. Used to choose a download location. */
    private suspend fun pickAndRegisterFolder(store: BetterMediaStore): String? {
        val treeUri = folderPicker?.invoke() ?: return null
        val folder = SafDocumentsProvider(applicationContext).queryDocument(treeUri) as? DocumentInfo.Folder ?: return null
        store.upsertBucket(BucketDraft(uri = folder.uri, name = folder.name))
        return folder.uri.toString()
    }

    /** Re-scans [folderUri] (and its children) in place, or every already-added root when null. */
    private suspend fun rescanKnownRoots(store: BetterMediaStore, folderUri: String?, onProgress: (Int) -> Unit): String? {
        val uris = if (folderUri != null) {
            listOf(Uri.parse(folderUri))
        } else {
            val roots = store.childBuckets(null)
            if (roots.isEmpty()) return "No folders to rescan — add one first"
            roots.map { it.uri }
        }
        return scanUris(store, uris, onProgress)
    }

    /** Scans each root [uris] into [store] via SAF + the bundled ffmpeg (content:// proxied over ymix),
     *  reporting the running file count and returning one combined summary line. */
    private suspend fun scanUris(store: BetterMediaStore, uris: List<Uri>, onProgress: (Int) -> Unit): String {
        val ffmpeg = File(applicationInfo.nativeLibraryDir, "libffmpeg.so").absolutePath
        val scanner = MediaScanner.create(
            store,
            SafDocumentsProvider(applicationContext),
            FfmpegMetadataExtractor(FfmpegMetadataGetter(ffmpeg, AndroidFfInputResolver(contentResolver))),
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

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val loader = builtImageLoader ?: return
        when {
            // In the background LRU list — decoded thumbnails are pure regenerable weight, drop all.
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> loader.trimMemoryCache(0f)
            // Running but memory is tight (or UI just hidden) — keep the hot half.
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> loader.trimMemoryCache(0.5f)
        }
    }
}

/** Cadence of the webview-js remote-update check (a saved copy applies at the next boot). */

/**
 * Hands the TextureView window to [MsePlayer]. MiniPlayer ↔ Now Playing re-parents the same
 * TextureView; releasing the SurfaceTexture there tears MediaCodec and glitches audio. Keep the
 * producer and restore it after re-attach (`setSurfaceTexture`); only bind MediaCodec once.
 */
private class MseSurfaceBridge(private val textureView: TextureView) {
    private var player: MsePlayer? = null
    private var pending: Surface? = null
    private var texture: SurfaceTexture? = null

    fun attach(p: MsePlayer) {
        player = p
        pending?.let { p.setVideoSurface(MseVideoSurface(it)) }
    }

    fun reattach(tv: TextureView) {
        val st = texture ?: return
        if (tv.surfaceTexture === st) return
        runCatching { tv.setSurfaceTexture(st) }
    }

    val listener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
            if (texture == null) {
                texture = st
                pending = Surface(st)
                player?.setVideoSurface(MseVideoSurface(pending))
                return
            }
            if (st !== texture) {
                texture?.let { kept -> runCatching { textureView.setSurfaceTexture(kept) } }
            }
        }

        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit

        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
            /* false = we own the SurfaceTexture; MediaCodec stays bound across reparent. */
            return false
        }

        override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
    }
}
