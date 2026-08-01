package ymusic.ui

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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import androidx.compose.ui.window.ComposeUIViewController
import kmphttp.KmpHttpClient
import kmphttp.RedirectOptions
import kmphttp.create
import kotlinx.cinterop.ExperimentalForeignApi
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSBundle
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSDate
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIView
import platform.UIKit.UIViewController
import ymusic.dynamicengine.DynamicEngineManager
import ymusic.dynamicengine.appleEngineCacheStore
import ymusic.dynamicengine.yt.embedPage
import ymusic.imageloader.DiskScaledImageCache
import ymusic.imageloader.ImageLoader
import ymusic.imageloader.fileFetcher
import ymusic.imageloader.networkFetcher
import ymusic.player.LocalPlayerContext
import ymusic.player.PlayerEngines
import ymusic.player.WebViewPlayerContext
import ymusic.player.createLocalPlayer
import ymusic.player.createPlayer
import ymusic.player.createWebViewPlayer
import ymusic.player.webview.APPLE_WEBVIEW_USER_AGENT
import ymusic.ytmedia.cache.DiskMediaBlobCache
import ymusic.ytmedia.cache.MediaCache
import ymusic.ytmedia.cache.SqlDelightSegmentIndex
import ymusic.ytmedia.cache.nativeSegmentDatabaseDriver
import ymusic.ytmedia.proxy.LocalMediaProxy

/**
 * The iOS entry point — the Swift app hosts `MainViewControllerKt.MainViewController()`. Builds the
 * [AppGraph] (scraping engine + player facade over the AVFoundation local + WKWebView YouTube
 * engines, the shared `UIView` as the video surface) and runs the same shared [App] the desktop and
 * Android shells run.
 */
@Suppress("FunctionName", "unused")
fun MainViewController(): UIViewController = ComposeUIViewController { IosAppRoot() }

@Composable
private fun IosAppRoot() {
    // The one shared video container both engines render into (the WebView injects its WKWebView).
    val container = remember { UIView() }
    var graph by remember { mutableStateOf<AppGraph?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            graph = buildIosGraph(container)
        } catch (t: Throwable) {
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

@OptIn(ExperimentalComposeUiApi::class, ExperimentalForeignApi::class)
private suspend fun buildIosGraph(container: UIView): AppGraph {
    // One live store shared by InnerTube HTTP and WKWebView's own YouTube requests.
    val cookies = IosWebViewCookieJar()
    val http = AppHttpClients(
        (NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).first() as String)
            .toPath()
            .resolve("ymusic-http-cache"),
    )
    val settings = IosSettingsStore()
    // First launch decides the app language (device if supported, else English) and persists it —
    // BEFORE the engine starts, because the engine pulls the same key as its content-language (hl).
    ensureAppLanguage(settings)
    val engines = DynamicEngineManager(
        http.engine,
        appVersion = 1,
        logger = IosLogger,
        cookieJar = cookies,
        settings = settings,
        cacheStore = appleEngineCacheStore(),
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

    // Player cache: WKWebView can't intercept https, so the injected bundle serves /videoplayback over
    // the message channel (see AppleProxyChannel). The proxy's client carries the shared
    // WKWebsiteDataStore cookies (the same store the WebView uses) + the WKWebView UA, so a forwarded
    // request's identity matches the WebView's own — BotGuard/attestation rejects a mismatch.
    val playerCacheRoot = (NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).first() as String)
        .toPath()
        .resolve("ymusic-player-cache")
    FileSystem.SYSTEM.createDirectories(playerCacheRoot)
    val cacheDriver = nativeSegmentDatabaseDriver() // shared: blob-span index + segment index
    val mediaProxy = LocalMediaProxy(
        cache = MediaCache(
            DiskMediaBlobCache(playerCacheRoot, FileSystem.SYSTEM, cacheDriver),
            SqlDelightSegmentIndex(
                cacheDriver,
                now = { (NSDate().timeIntervalSince1970 * 1000).toLong() },
            ),
        ),
        client = KmpHttpClient.create {
            cookieJar = cookies
            defaultRedirectOptions = RedirectOptions(followRedirects = true, followSslRedirects = true)
        },
        userAgent = APPLE_WEBVIEW_USER_AGENT,
    )

    val engineJs = loadWebViewJs()
    val player = createPlayer(
        PlayerEngines(
            local = { createLocalPlayer(LocalPlayerContext(container)) },
            webView = { createWebViewPlayer(WebViewPlayerContext(container, mediaProxy), engineJs, appVersion = 1, { ua -> engines.embedPage(ua) }) },
        ),
    )

    return AppGraph(
        engines = engines,
        player = player,
        mediaStore = null,
        scanLibraryFolder = null,
        imageLoader = imageLoader,
        settings = settings,
        surfaceScalesDown = true,
        loginBrowser = { url, state, onPageChanged, modifier ->
            IosLoginBrowser(url, state, onPageChanged, modifier)
        },
    ) { modifier -> UIKitView(factory = { container }, modifier = modifier, interactive = true) }
}

/** The built `webview-js` bundle, shipped as an app-bundle resource (see the Xcode project). */
@OptIn(ExperimentalForeignApi::class)
private fun loadWebViewJs(): String {
    val path = NSBundle.mainBundle.pathForResource("webview_ymusic", ofType = "js")
        ?: return "console.error('webview_ymusic.js missing from app bundle')"
    return NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null) ?: ""
}
