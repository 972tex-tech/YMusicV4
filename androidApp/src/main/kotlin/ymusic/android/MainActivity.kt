package ymusic.android

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebSettings
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import kmpcommon.uri.Uri
import kmpffmpeg.AndroidFfInputResolver
import kmpffmpeg.metadata.FfmpegMetadataGetter
import kmphttp.KmpHttpClient
import kmphttp.RedirectOptions
import kmphttp.create
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath
import ymusic.dynamicengine.yt.embedPage
import ymusic.mediascanner.DocumentInfo
import ymusic.mediascanner.FfmpegMetadataExtractor
import ymusic.mediascanner.MediaScanner
import ymusic.mediascanner.SafDocumentsProvider
import ymusic.mediastore.BetterMediaStore
import ymusic.mediastore.model.BucketDraft
import ymusic.player.LocalPlayerContext
import ymusic.player.PlayerEngines
import ymusic.player.WebViewPlayerContext
import ymusic.player.createLocalPlayer
import ymusic.player.createPlayer
import ymusic.player.createWebViewPlayer
import ymusic.ui.App
import ymusic.ui.AppGraph
import ymusic.ui.WebViewSabrSpecResolver
import ymusic.ui.downloadCaptionPreference
import ymusic.ui.downloadLocationFolder
import ymusic.ytmedia.cache.DiskMediaBlobCache
import ymusic.ytmedia.cache.MediaCache
import ymusic.ytmedia.cache.SqlDelightBlobIndex
import ymusic.ytmedia.cache.SqlDelightSegmentIndex
import ymusic.ytmedia.cache.androidSegmentDatabaseDriver
import ymusic.ytmedia.proxy.LocalMediaProxy
import java.io.File

/**
 * The Android shell: assembles the [AppGraph] (ExoPlayer local + WebView YouTube engines, the
 * scraping engine manager) and boots the shared :commonUi [App]. Background playback (the
 * MediaSessionService) lands with the system-integration milestone.
 */
class MainActivity : ComponentActivity() {
    private var player: ymusic.player.Player? = null

    // Bridges the callback-based SAF folder pick to the shell's suspend ScanAction: launch() fires the
    // system ACTION_OPEN_DOCUMENT_TREE, the result callback completes the pending deferred.
    private var pendingPick: CompletableDeferred<Uri?>? = null
    private val openFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
            if (treeUri != null) {
                // Persist read access so a rescan (and next launch) can reach the folder without re-picking.
                contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            pendingPick?.complete(treeUri?.let { Uri.parse(it.toString()) })
            pendingPick = null
        }

    /** Launches the SAF folder picker and suspends until the user picks a tree (or cancels → null). */
    private suspend fun pickFolder(): Uri? {
        pendingPick?.complete(null) // a prior pick still open → abandon it
        val deferred = CompletableDeferred<Uri?>()
        pendingPick = deferred
        withContext(Dispatchers.Main) { openFolder.launch(null) }
        return deferred.await()
    }

    override fun onDestroy() {
        super.onDestroy()
        // The player is the Activity-scoped half of the graph (its engines render into this
        // activity's views): recreation builds a fresh one, so release the old engines' WebView /
        // ExoPlayer instead of stacking one per rotation. Playback continuity across recreation is
        // the MediaSessionService milestone, not this teardown's job.
        player?.release()
        player = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is light-only (commonUi LightScheme paints white behind the bars), so the bar
        // icon color must not follow the SYSTEM dark-mode setting — that yields white-on-white.
        val light = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = light, navigationBarStyle = light)
        FreezeWatchdog.start()
        lifecycle.addObserver(
            androidx.lifecycle.LifecycleEventObserver { _, event ->
                android.util.Log.i("FreezeWatchdog", "lifecycle $event")
            },
        )
        setContent {
            CompositionClockProbe()
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
        // The process half (http/engines/store/settings/images) survives every recreation of this
        // activity; only the view-bound player is built here, around this activity's container.
        val shared = (application as YMusicApplication).services.await()

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
        ).also { this.player = it }

        // Warm the YouTube engine ~5s after startup so the first play/download skips the WebView +
        // /embed build (mirrors legacy warmUpYtPlayer). On the main thread — it hosts a platform WebView.
        CoroutineScope(Dispatchers.Main).launch {
            kotlinx.coroutines.delay(5_000)
            player.prepareYoutubeEngine()
        }

        // Offline downloads: SABR download stack + on-device ffmpeg (libffmpeg.so). The spec resolver
        // drives the playback WebView to prime each video's player response + PO token.
        val downloadService = shared.webViewJs?.let {
            val ffmpegBin = File(applicationInfo.nativeLibraryDir, "libffmpeg.so")
                .takeIf { f -> f.exists() }?.absolutePath
            val downloadsDir = File(getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC) ?: filesDir, "downloads")
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
                // Okio storage for now; a SAF-backed DownloadStorage writing into a user-picked Library
                // folder (content://) is on the board.
                storage = ymusic.download.OkioDownloadStorage(
                    FileSystem.SYSTEM,
                    File(cacheDir, "download-work").absolutePath.toPath(),
                    downloadsDir.absolutePath.toPath(),
                    // Publishes into a user-picked SAF folder (content://); okio handles file:// / default.
                    contentPublisher = SafContentPublisher(applicationContext),
                ),
                mediaCache = playerCache, // read-only (see YtStreamDownloader): reuse cached playback bytes
                // Own segment index for SABR resume (byte range per segment), separate from the player cache DB.
                segmentIndex = SqlDelightSegmentIndex(
                    androidSegmentDatabaseDriver(applicationContext, name = "download_segments.db"),
                    now = { System.currentTimeMillis() },
                ),
                captionPreference = { shared.settings.downloadCaptionPreference() },
                downloadFolder = { shared.settings.downloadLocationFolder() }, // SAF folder (content://) or default
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
        val treeUri = pickFolder() ?: return null
        return scanUris(store, listOf(treeUri), onProgress)
    }

    /** Pick a folder and register it as a Library bucket (no media scan — works even when empty),
     *  returning its uri. Used to choose a download location. */
    private suspend fun pickAndRegisterFolder(store: BetterMediaStore): String? {
        val treeUri = pickFolder() ?: return null
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
            progress.errors.forEach { android.util.Log.w("LibraryScan", "scan error at ${it.uri}", it.cause) }
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
}

@androidx.compose.runtime.Composable
private fun Booting(content: @androidx.compose.runtime.Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
