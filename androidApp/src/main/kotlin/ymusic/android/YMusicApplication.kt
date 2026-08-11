package ymusic.android

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Intent
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebSettings
import android.widget.FrameLayout
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import io.sentry.kotlin.multiplatform.Sentry
import kmpcommon.uri.Uri
import kmpffmpeg.AndroidFfInputResolver
import kmpffmpeg.metadata.FfmpegMetadataGetter
import kmphttp.KmpHttpClient
import kmphttp.RedirectOptions
import kmphttp.create
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath
import ymusic.download.DownloadChange
import ymusic.dynamicengine.DynamicEngineManager
import ymusic.dynamicengine.androidEngineCacheStore
import ymusic.dynamicengine.yt.embedPage
import ymusic.imageloader.DiskScaledImageCache
import ymusic.imageloader.ImageLoader
import ymusic.imageloader.defaultMemoryCacheBudget
import ymusic.imageloader.fileFetcher
import ymusic.imageloader.networkFetcher
import ymusic.mediascanner.DocumentInfo
import ymusic.mediascanner.FfmpegMetadataExtractor
import ymusic.mediascanner.MediaScanner
import ymusic.mediascanner.SafDocumentsProvider
import ymusic.mediastore.BetterMediaStore
import ymusic.mediastore.mediaStoreAndroidDriver
import ymusic.mediastore.model.BucketDraft
import ymusic.player.LocalPlayerContext
import ymusic.player.PlayerEngines
import ymusic.player.WebViewPlayerContext
import ymusic.player.createLocalPlayer
import ymusic.player.createPlayer
import ymusic.player.createWebViewPlayer
import ymusic.player.isPlaying
import ymusic.ui.AppGraph
import ymusic.ui.AppHttpClients
import ymusic.ui.WebViewSabrSpecResolver
import ymusic.ui.downloadCaptionPreference
import ymusic.ui.downloadLocationFolder
import ymusic.ui.ensureAppLanguage
import ymusic.ui.mediaStoreArtFetcher
import ymusic.ui.registerCompletedDownload
import ymusic.ytmedia.cache.DiskMediaBlobCache
import ymusic.ytmedia.cache.MediaCache
import ymusic.ytmedia.cache.SqlDelightBlobIndex
import ymusic.ytmedia.cache.SqlDelightSegmentIndex
import ymusic.ytmedia.cache.androidSegmentDatabaseDriver
import ymusic.ytmedia.proxy.LocalMediaProxy
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
    val mediaStore: BetterMediaStore,
    /** The injected YouTube player script (webview-js/dist, bundled as an asset); null = the
     *  WebView engine is absent and a YouTube item surfaces E60050. */
    val webViewJs: String?,
    val imageLoader: ImageLoader,
)

class YMusicApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.SENTRY_DSN.isNotEmpty()) {
            Sentry.init {
                it.dsn = BuildConfig.SENTRY_DSN
                it.environment =
                    if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) "debug"
                    else "production"
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Main-thread, app-lifetime scope for playback glue (service start, audio focus). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The current Activity's SAF folder pick, registered while it lives. The graph is app-scoped
     *  but a pick needs an Activity; a scan is always user-driven, so one is up when this runs. */
    @Volatile var folderPicker: (suspend () -> Uri?)? = null

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
        // The cookie store is the WebView CookieManager — shared with the WebView YouTube player,
        // persistent across launches (as legacy YMusic did). The engine's OWN HTTP client carries
        // NO jar: the engine JS cooks every Cookie header itself against this store (cookie/get|set
        // protocol), so cookie policy lives in the remotely-updatable bundle.
        val cookies = WebViewCookieJar()
        // One client stack, one disk cache, shared by the engine and the player's embed fetch.
        val http = AppHttpClients(cacheDir.resolve("http-cache").toOkioPath())
        val settings = PrefsSettingsStore.open(this)
        // First launch decides the app language (device if supported, else English) and persists
        // it — BEFORE the engine starts: the engine pulls the same key as its content-language.
        ensureAppLanguage(settings)
        val engines = DynamicEngineManager(
            http.engine,
            appVersion = 1,
            logger = LogcatLogger(),
            cacheStore = androidEngineCacheStore(this),
            cookieJar = cookies,
            settings = settings,
            // Debug builds crash on an engine-thread stall (StrictMode-style); release only logs.
            strictStints = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0,
        )
        engines.start()

        val mediaStore = BetterMediaStore.create(mediaStoreAndroidDriver(this))

        val webViewJs = runCatching {
            assets.open("webview_ymusic.js").readBytes().decodeToString()
        }.getOrNull()

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
        val imageLoader = ImageLoader(
            fetchers = listOfNotNull(
                imageNetworkFetcher,
                fileFetcher(),
                ffmpegBin?.let {
                    mediaStoreArtFetcher(
                        FfmpegMetadataGetter(it, AndroidFfInputResolver(contentResolver)),
                        mediaStore,
                        imageNetworkFetcher,
                    )
                },
            ),
            scaledCache = DiskScaledImageCache(FileSystem.SYSTEM, cacheDir.resolve("image-scaled-cache").toOkioPath()),
            logger = LogcatLogger(),
            memoryCacheBytes = defaultMemoryCacheBudget(this),
        ).also { builtImageLoader = it }

        return SharedServices(http, cookies, settings, engines, mediaStore, webViewJs, imageLoader)
    }

    private suspend fun buildGraph(): AppGraph {
        // One shared video host (a FrameLayout) both engines render into: the WebView engine adds its
        // WebView, the local engine adds its SurfaceView, each owning its own view's visibility.
        // Views must be born on the main thread; everything else builds off it (see [services]).
        val videoContainer = FrameLayout(this)
        val graph = withContext(Dispatchers.Default) {
            buildGraphOffMain(videoContainer)
        }
        // The session/notification host must be up whenever playback is — every playing transition
        // (re)starts it. Playback only ever starts from a user action with the app foregrounded, so
        // the FGS-start restriction doesn't bite; if it ever does, media3 notification actions and
        // media buttons can still revive the service.
        appScope.launch {
            graph.player.status.map { it.state.isPlaying() }.distinctUntilChanged().filter { it }.collect {
                try {
                    ContextCompat.startForegroundService(
                        this@YMusicApplication,
                        Intent(this@YMusicApplication, PlaybackService::class.java),
                    )
                } catch (e: IllegalStateException) {
                    Log.w("YMusic", "PlaybackService start rejected", e)
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
                            Log.w("YMusic", "DownloadForegroundService start rejected", e)
                        }
                    }
                }
            }
        }
        return graph
    }

    private suspend fun buildGraphOffMain(videoContainer: FrameLayout): AppGraph {
        val shared = services.await()

        // Player cache: the WebView bridge intercepts the bundle's /ymusicForward (rewritten from
        // /videoplayback) and serves it from this router — hit → synthesized bytes, miss → forwarded and
        // teed to disk. Its client carries the WebView CookieManager cookies (shared.cookies) + the
        // WebView's own default UA; a mismatched identity gets rejected by googlevideo/BotGuard.
        // The player cache; shared read-only with the download stack so a download reuses segments already
        // cached from playback (never writing into it).
        val playerCache = run {
            val cacheRoot = File(applicationContext.cacheDir, "player-cache").apply { mkdirs() }
            val driver = androidSegmentDatabaseDriver(applicationContext) // shared: span index + segment index
            MediaCache(
                DiskMediaBlobCache(cacheRoot.toOkioPath(), FileSystem.SYSTEM, SqlDelightBlobIndex(driver, now = { System.currentTimeMillis() })),
                SqlDelightSegmentIndex(driver, now = { System.currentTimeMillis() }),
            )
        }
        val mediaProxy = shared.webViewJs?.let {
            val client = KmpHttpClient.create {
                cookieJar = shared.cookies
                defaultRedirectOptions = RedirectOptions(followRedirects = true, followSslRedirects = true)
            }
            LocalMediaProxy(
                playerCache,
                client,
                userAgent = WebSettings.getDefaultUserAgent(applicationContext),
                logger = LogcatLogger(),
                verbose = true,
            )
        }

        // Captured from the WebView-engine factory so the download service can prime SABR inputs off the
        // same page (attestation lives in the running page).
        var downloadWebViewPlayer: ymusic.player.WebViewPlayer? = null
        val player = createPlayer(
            PlayerEngines(
                local = { createLocalPlayer(LocalPlayerContext(this, videoContainer)) },
                webView = shared.webViewJs?.let { js ->
                    {
                        createWebViewPlayer(
                            WebViewPlayerContext(this, videoContainer, mediaProxy),
                            engineJs = js,
                            appVersion = 5000,
                            embedHtml = { ua -> shared.engines.embedPage(ua) },
                        ).also { downloadWebViewPlayer = it }
                    }
                },
            ),
        )

        // Warm the YouTube engine ~5s after startup so the first play/download skips the WebView +
        // /embed build (mirrors legacy warmUpYtPlayer). On the main thread — it hosts a platform WebView.
        appScope.launch {
            kotlinx.coroutines.delay(5_000)
            player.prepareYoutubeEngine()
        }

        // Offline downloads: SABR download stack + on-device ffmpeg (libffmpeg.so). The spec resolver
        // drives the playback WebView to prime each video's player response + PO token.
        val downloadService = shared.webViewJs?.let {
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
                cookieJar = shared.cookies
                defaultRedirectOptions = RedirectOptions(followRedirects = false, followSslRedirects = false)
            }
            ymusic.download.DownloadService(
                store = ymusic.download.DownloadStore(ymusic.download.downloadDatabaseAndroidDriver(applicationContext)),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                now = { System.currentTimeMillis() },
                resolver = ymusic.download.SabrSpecResolver { videoId ->
                    // Force-init the WebView engine if it isn't up yet (the factory sets downloadWebViewPlayer).
                    val page = downloadWebViewPlayer ?: run {
                        withContext(Dispatchers.Main) { player.prepareYoutubeEngine() }
                        downloadWebViewPlayer
                    } ?: error("YouTube engine unavailable")
                    WebViewSabrSpecResolver(page).resolve(videoId)
                },
                client = sabrClient,
                userAgent = WebSettings.getDefaultUserAgent(applicationContext),
                ffmpeg = ffmpegBin?.let { bin -> ymusic.download.Ffmpeg(bin) },
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
                captionPreference = { shared.settings.downloadCaptionPreference() },
                // The user's chosen download location (SAF content:// or file://) — always set: the download
                // flow requires picking one before enqueue.
                downloadFolder = { shared.settings.downloadLocationFolder()!! },
                onCompleted = { task, result ->
                    registerCompletedDownload(shared.mediaStore, shared.settings, downloadScanner, task, result)
                },
                logger = LogcatLogger(),
            )
        }

        val versionName = packageManager.getPackageInfo(packageName, 0).versionName
        return AppGraph(
            shared.engines,
            player,
            mediaStore = shared.mediaStore,
            scanLibraryFolder = { onProgress -> scanPickedFolder(shared.mediaStore, onProgress) },
            libraryFolderPicker = { pickAndRegisterFolder(shared.mediaStore) },
            rescanLibrary = { onProgress -> rescanKnownRoots(shared.mediaStore, onProgress) },
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
            downloads = downloadService,
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

    /** Re-scans every already-added root folder in place (no pick), reconciling deletions/edits. */
    private suspend fun rescanKnownRoots(store: BetterMediaStore, onProgress: (Int) -> Unit): String? {
        val roots = store.childBuckets(null)
        if (roots.isEmpty()) return "No folders to rescan — add one first"
        return scanUris(store, roots.map { it.uri }, onProgress)
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
            progress.errors.forEach { Log.w("LibraryScan", "scan error at ${it.uri}", it.cause) }
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
