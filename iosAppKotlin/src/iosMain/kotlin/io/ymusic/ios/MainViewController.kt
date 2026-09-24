package io.ymusic.ios

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.window.ComposeUIViewController
import io.ymusic.dynamicengine.DynamicEngineManager
import io.ymusic.dynamicengine.appleEngineCacheStore
import io.ymusic.dynamicengine.embeddedEngineJsSource
import io.ymusic.imageloader.DiskScaledImageCache
import io.ymusic.imageloader.ImageLoader
import io.ymusic.imageloader.fileFetcher
import io.ymusic.imageloader.networkFetcher
import io.ymusic.mse.MseVideoSurface
import io.ymusic.player.LocalPlayerContext
import io.ymusic.player.PlayerEngines
import io.ymusic.player.createLocalPlayer
import io.ymusic.player.createPlayer
import io.ymusic.player.mse.MsePlayer
import io.ymusic.ui.App
import io.ymusic.ui.AppGraph
import io.ymusic.ui.AppHttpClients
import io.ymusic.ui.ensureAppLanguage
import io.ymusic.ytmedia.cache.DiskMediaBlobCache
import io.ymusic.ytmedia.cache.MediaCache
import io.ymusic.ytmedia.cache.SqlDelightBlobIndex
import io.ymusic.ytmedia.cache.SqlDelightSegmentIndex
import io.ymusic.ytmedia.cache.nativeSegmentDatabaseDriver
import io.ymusic.ytmedia.proxy.LocalMediaProxy
import kmphttp.KmpHttpClient
import kmphttp.RedirectOptions
import kmphttp.create
import kotlinx.cinterop.ExperimentalForeignApi
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSDate
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIView
import platform.UIKit.UIViewController
/**
 * The iOS entry point — the Swift app hosts `MainViewControllerKt.MainViewController()`. Builds the
 * [AppGraph] (scraping engine + player facade over AVFoundation local + MSE YouTube, VideoToolbox
 * hole) and runs the same shared [App] the desktop and Android shells run.
 */
@Suppress("FunctionName", "unused")
fun MainViewController(): UIViewController = ComposeUIViewController { IosAppRoot() }

@Composable
private fun IosAppRoot() {
    // Host for the AVFoundation local player; YouTube MSE paints into [IosMseVideo].
    val container = remember { UIView() }
    var graph by remember { mutableStateOf<AppGraph?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            graph = buildIosGraph(container)
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c // the effect was disposed — not a boot failure
        } catch (t: Throwable) {
            IosLogger().fork("boot").captureError(t)
            error = t.message ?: t.toString()
        }
    }

    val g = graph
    when {
        error != null -> Center { Text("Startup failed: $error", color = MaterialTheme.colorScheme.error) }
        g == null -> Center { CircularProgressIndicator() }
        else -> App(g)
    }
}

@Composable
private fun Center(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@OptIn(ExperimentalForeignApi::class)
private suspend fun buildIosGraph(container: UIView): AppGraph {
    val rootLogger = IosLogger()
    val cookies = IosWebViewCookieJar()
    val http = AppHttpClients(
        (NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).first() as String)
            .toPath()
            .resolve("ymusic-http-cache"),
        logger = rootLogger,
    )
    val settings = IosSettingsStore()
    // First launch decides the app language (device if supported, else English) and persists it —
    // BEFORE the engine starts, because the engine pulls the same key as its content-language (hl).
    ensureAppLanguage(settings)
    val cacheStore = appleEngineCacheStore(logger = rootLogger)
    val engines = DynamicEngineManager(
        http.engine,
        appVersion = 5000,
        jsSource = embeddedEngineJsSource(),
        logger = rootLogger,
        cookieJar = cookies,
        settings = settings,
        cacheStore = cacheStore,
    )
    engines.start()

    val cacheRoot = (NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).first() as String)
        .toPath()
        .resolve("ymusic-images")
    val imageLoader = ImageLoader(
        fetchers = listOf(
            networkFetcher(http.image, FileSystem.SYSTEM, cacheRoot.resolve("source")),
            fileFetcher(),
        ),
        scaledCache = DiskScaledImageCache(FileSystem.SYSTEM, cacheRoot.resolve("scaled")),
    )

    val playerCacheRoot = (NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).first() as String)
        .toPath()
        .resolve("ymusic-player-cache")
    FileSystem.SYSTEM.createDirectories(playerCacheRoot)
    val cacheDriver = nativeSegmentDatabaseDriver()
    val mediaProxy = LocalMediaProxy(
        cache = MediaCache(
            DiskMediaBlobCache(playerCacheRoot, FileSystem.SYSTEM, SqlDelightBlobIndex(cacheDriver, now = { (NSDate().timeIntervalSince1970 * 1000).toLong() })),
            SqlDelightSegmentIndex(
                cacheDriver,
                now = { (NSDate().timeIntervalSince1970 * 1000).toLong() },
            ),
        ),
        client = KmpHttpClient.create {
            cookieJar = cookies
            defaultRedirectOptions = RedirectOptions(followRedirects = true, followSslRedirects = true)
        },
        userAgent = MSE_CHROME_UA,
    )

    val mseHole = IosMseHole()
    val mseSurface = MseVideoSurface().apply { layer = mseHole.layerPtr() }
    val msePlayer = MsePlayer.create(
        client = http.engine,
        appVersion = 5000,
        logger = rootLogger,
        cacheStore = cacheStore,
        cookieJar = cookies,
        settings = settings,
        videoSurface = mseSurface,
        main = engines,
        mediaProxy = mediaProxy,
    )
    val player = createPlayer(
        PlayerEngines(
            local = { createLocalPlayer(LocalPlayerContext(container)) },
            webView = { msePlayer },
        ),
    )

    return AppGraph(
        engines = engines,
        player = player,
        logger = rootLogger,
        mediaStore = null,
        scanLibraryFolder = null,
        imageLoader = imageLoader,
        settings = settings,
        surfaceScalesDown = true,
        loginBrowser = { url, state, onPageChanged, modifier ->
            IosLoginBrowser(url, state, onPageChanged, modifier)
        },
    ) { modifier -> IosMseVideo(mseHole, msePlayer, modifier) }
}

private const val MSE_CHROME_UA =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36"
