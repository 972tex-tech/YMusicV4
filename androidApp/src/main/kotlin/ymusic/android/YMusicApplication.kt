package ymusic.android

import android.app.Application
import android.content.ComponentCallbacks2
import kmphttp.KmpHttpClient
import kmphttp.create
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import ymusic.dynamicengine.DynamicEngineManager
import ymusic.dynamicengine.androidEngineCacheStore
import ymusic.imageloader.DiskScaledImageCache
import ymusic.imageloader.ImageLoader
import ymusic.imageloader.defaultMemoryCacheBudget
import ymusic.imageloader.fileFetcher
import ymusic.imageloader.networkFetcher
import ymusic.mediastore.BetterMediaStore
import ymusic.mediastore.mediaStoreAndroidDriver
import ymusic.ui.AppHttpClients

/**
 * The PROCESS-scoped half of the app graph: everything that must exist exactly once no matter how
 * many times the Activity is recreated. AppHttpClients is the load-bearing member — a second
 * instance would open a second okhttp Cache over the same directory, which corrupts it (see its
 * KDoc) — but the engine manager, media store, settings and image loader equally have no business
 * dying with a rotation. The Activity keeps only what is view-bound: the shared video container
 * and the player built around it.
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // For onTrimMemory, which cannot await the deferred; set once the build completes.
    @Volatile private var builtImageLoader: ImageLoader? = null

    /** Built off-main on FIRST await, not in onCreate — the first CookieManager.getInstance()
     *  alone initializes the whole WebView provider, which froze a slow device for seconds. */
    val services: Deferred<SharedServices> by lazy { scope.async { buildServices() } }

    private suspend fun buildServices(): SharedServices {
        // The cookie store is the WebView CookieManager — shared with the WebView YouTube player,
        // persistent across launches (as legacy YMusic did). The engine's OWN HTTP client carries
        // NO jar: the engine JS cooks every Cookie header itself against this store (cookie/get|set
        // protocol), so cookie policy lives in the remotely-updatable bundle.
        val cookies = WebViewCookieJar()
        // One client stack, one disk cache, shared by the engine and the player's embed fetch.
        val http = AppHttpClients(cacheDir.resolve("http-cache").toOkioPath())
        val settings = PrefsSettingsStore.open(this)
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
        // the fetcher's disk cache, decode from file) + local files. Memory budget scales with the
        // device's heap class; onTrimMemory (below) sheds it under pressure.
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
        ).also { builtImageLoader = it }

        return SharedServices(http, cookies, settings, engines, mediaStore, webViewJs, imageLoader)
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
