package ymusic.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ymusic.dynamicengine.DynamicEngineManager
import ymusic.dynamicengine.yt.YtContext
import ymusic.mediastore.BetterMediaStore
import ymusic.player.MediaEngine
import ymusic.player.Player

/**
 * Everything the shared UI needs, built once by the platform shell (the composition root): the
 * running engine manager, the player facade, and the platform video surface. [playerSurface]
 * renders the active engine's picture (SwingPanel over VLCJ/JCEF on desktop, AndroidView over
 * SurfaceView/WebView on Android) — the shell owns the platform views, the UI only places them.
 *
 * [mediaStore] backs the Library screen; a shell without one (no local library yet) leaves it null.
 * [scanLibraryFolder] is the shell's directory-pick + scan action (desktop's FileDialog + media
 * scanner): it returns a user-facing summary line, or null when the pick was canceled.
 */
class AppGraph(
    val engines: DynamicEngineManager,
    val player: Player,
    val ytContext: YtContext = YtContext(),
    val mediaStore: BetterMediaStore? = null,
    val scanLibraryFolder: (suspend () -> String?)? = null,
    val playerSurface: @Composable (engine: MediaEngine<*>?, modifier: Modifier) -> Unit,
)
