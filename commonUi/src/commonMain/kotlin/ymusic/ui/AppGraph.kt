package ymusic.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.MutableSharedFlow
import ymusic.dynamicengine.DynamicEngineManager
import ymusic.dynamicengine.yt.YtContext
import ymusic.imageloader.ImageLoader
import ymusic.mediastore.BetterMediaStore
import ymusic.player.Player
import ymusic.settings.InMemorySettingsStore
import ymusic.settings.SettingsStore

internal const val PlaybackQualitySettingKey = "player.preferVideoQuality"

/**
 * Everything the shared UI needs, built once by the platform shell (the composition root): the
 * running engine manager, the player facade, and the platform video surface. [playerSurface] hosts
 * the **one** shared video container the shell handed to the engines (a Swing panel on desktop, a
 * `FrameLayout` on Android) — each engine attaches its own surface (WebView / SurfaceView / VLCJ)
 * into it and owns that surface's visibility, so the UI never needs to know which engine is active.
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
    /**
     * Re-scans the already-added root folders in place (no folder pick), returning a user-facing
     * summary line. Null when the shell can't scan, or has no notion of persisted roots.
     */
    val rescanLibrary: (suspend () -> String?)? = null,
    /** Display version for the drawer header (e.g. "4.0.1 · arm64"); null hides the line. */
    val appVersion: String? = null,
    /** Thumbnail loading; null keeps the placeholder boxes (e.g. a shell without a cache dir yet). */
    val imageLoader: ImageLoader? = null,
    /** App settings (UI language, hl/gl, …); the in-memory default works but doesn't persist. */
    val settings: SettingsStore = InMemorySettingsStore(),
    /**
     * True when [playerSurface] may be shown at any size (desktop's OSR browser renders a fixed
     * logical viewport) — the mini player then hosts it as its 36dp art tile. False for a windowed
     * WebView (Android): the page sizes its streams to the real viewport, so a thumbnail-sized
     * surface kills playback and the UI must keep a real-size docked preview instead.
     */
    val surfaceScalesDown: Boolean = false,
    val playerSurface: @Composable (modifier: Modifier) -> Unit,
) {
    /** App-wide reload signal (desktop F5/Cmd+R): the visible feed surface refetches. */
    val refreshRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    fun requestRefresh() {
        refreshRequests.tryEmit(Unit)
    }
}
