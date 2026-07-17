package ymusic.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kmphttp.KmpHttpClient
import kmphttp.create
import ymusic.imageloader.ImageLoader
import ymusic.imageloader.defaultMemoryCacheBudget
import ymusic.ui.CatalogueScreen
import ymusic.ui.LocalImageLoader
import ymusic.ui.YMusicTheme

/**
 * Standalone UI-component catalogue — `./gradlew :desktopApp:catalogue`. Boots NOTHING but a
 * window and a bare image loader (no CEF, no engines, no media store), so the design-feedback
 * loop is seconds. Same screen is also inside the app (drawer → "UI catalogue").
 */
fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "YMusic · UI catalogue",
        state = rememberWindowState(size = DpSize(460.dp, 940.dp)),
    ) {
        val loader = remember {
            ImageLoader(
                client = KmpHttpClient.create { },
                logger = StdoutLogger,
                memoryCacheBytes = defaultMemoryCacheBudget(),
            )
        }
        YMusicTheme {
            CompositionLocalProvider(LocalImageLoader provides loader) {
                CatalogueScreen()
            }
        }
    }
}
