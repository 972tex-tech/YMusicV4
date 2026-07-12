package ymusic.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ymusic.dynamicengine.DynamicEngineManager
import ymusic.dynamicengine.yt.YtContext
import ymusic.player.MediaEngine
import ymusic.player.Player

/**
 * Everything the shared UI needs, built once by the platform shell (the composition root): the
 * running engine manager, the player facade, and the platform video surface. [playerSurface]
 * renders the active engine's picture (SwingPanel over VLCJ/JCEF on desktop, AndroidView over
 * SurfaceView/WebView on Android) — the shell owns the platform views, the UI only places them.
 */
class AppGraph(
    val engines: DynamicEngineManager,
    val player: Player,
    val ytContext: YtContext = YtContext(),
    val playerSurface: @Composable (engine: MediaEngine<*>?, modifier: Modifier) -> Unit,
)
