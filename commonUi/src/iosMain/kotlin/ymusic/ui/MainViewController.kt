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
import kmphttp.InMemoryCookieJar
import kmphttp.KmpHttpClient
import kmphttp.create
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSBundle
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile
import platform.UIKit.UIView
import platform.UIKit.UIViewController
import ymusic.dynamicengine.DynamicEngineManager
import ymusic.player.LocalPlayerContext
import ymusic.player.PlayerEngines
import ymusic.player.WebViewPlayerContext
import ymusic.player.createLocalPlayer
import ymusic.player.createPlayer
import ymusic.player.createWebViewPlayer

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
    // A live cookie jar (NOT NO_COOKIES) so the engine carries YouTube's anonymous session cookies.
    val httpClient = KmpHttpClient.create { cookieJar = InMemoryCookieJar() }
    val engines = DynamicEngineManager(httpClient, appVersion = 1)
    engines.start()

    val engineJs = loadWebViewJs()
    val player = createPlayer(
        PlayerEngines(
            local = { createLocalPlayer(LocalPlayerContext()) },
            webView = { createWebViewPlayer(WebViewPlayerContext(container), engineJs, appVersion = 1, httpClient) },
        ),
    )

    return AppGraph(engines, player, mediaStore = null, scanLibraryFolder = null) { modifier ->
        UIKitView(factory = { container }, modifier = modifier)
    }
}

/** The built `webview-js` bundle, shipped as an app-bundle resource (see the Xcode project). */
@OptIn(ExperimentalForeignApi::class)
private fun loadWebViewJs(): String {
    val path = NSBundle.mainBundle.pathForResource("webview_ymusic", ofType = "js")
        ?: return "console.error('webview_ymusic.js missing from app bundle')"
    return NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null) ?: ""
}
