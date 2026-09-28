package io.ymusic.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import io.ymusic.dynamicengine.DynamicEngineManager
import io.ymusic.imageloader.ImageLoader
import io.ymusic.mediastore.BetterMediaStore
import io.ymusic.player.Player
import io.ymusic.remoteconfig.StatUsage
import io.ymusic.settings.InMemorySettingsStore
import io.ymusic.settings.SettingsStore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/** The player-quality settings key — public because shell settings stores (iOS NSUserDefaults
 *  seeding) enumerate the keys they persist. */
const val PlaybackQualitySettingKey = "player.preferVideoQuality"
const val PlaybackVolumeKey = "player.volume"
const val PlaybackSpeedKey = "player.speed"
const val PlaybackPitchKey = "player.pitch"
const val PlaybackEqKey = "player.eq"

/**
 * A shell scan/rescan action: reports the running scanned-file count through [onProgress] while it
 * works, and returns a user-facing summary line (or null when a folder pick was canceled).
 */
typealias ScanAction = suspend (onProgress: (scanned: Int) -> Unit) -> String?

/**
 * Everything the shared UI needs, built once by the platform shell (the composition root): the
 * running engine manager, the player facade, and the platform video surface. [playerSurface] hosts
 * the **one** shared video container the shell handed to the engines (a Swing panel on desktop, a
 * `FrameLayout` on Android) — each engine attaches its own surface (WebView / SurfaceView / ffmpeg)
 * into it and owns that surface's visibility, so the UI never needs to know which engine is active.
 *
 * [mediaStore] backs the Library screen; a shell without one (no local library yet) leaves it null.
 * [scanLibraryFolder] is the shell's directory-pick + scan action (desktop's FileDialog + media
 * scanner): it returns a user-facing summary line, or null when the pick was canceled.
 */
class AppGraph(
    val engines: DynamicEngineManager,
    val player: Player,
    val mediaStore: BetterMediaStore? = null,
    val scanLibraryFolder: ScanAction? = null,
    /**
     * Opens the OS folder picker and registers the chosen folder as a Library bucket (a lightweight
     * `upsertBucket`, so it works even for an empty folder — no media scan), returning its bucket uri,
     * or null if the user canceled. Unlike [scanLibraryFolder] (which scans + reports a summary for the
     * Library pill), the caller needs the picked folder's identity — used to set a download location.
     * Null on shells without a folder picker.
     */
    val libraryFolderPicker: (suspend () -> String?)? = null,
    /**
     * Re-scans the already-added root folders in place (no folder pick). Null when the shell can't
     * scan, or has no notion of persisted roots.
     */
    val rescanLibrary: ScanAction? = null,
    /**
     * Permanently deletes the item's FILE from storage (okio delete on desktop, SAF
     * DocumentsContract on Android) — the destructive half of the Library's "Remove from disk";
     * the UI removes the media-store row only after this returns. Throws on failure (surfaced in
     * the confirm dialog). Null hides the menu action entirely.
     */
    val deleteFromDisk: (suspend (io.ymusic.mediastore.model.MediaStoreItem) -> Unit)? = null,
    /** Reveal a file (by its uri) in the OS file manager — desktop only (mac Finder / file explorer);
     *  null on mobile, which hides the "Reveal in Finder" download action. */
    val revealInFolder: (suspend (uri: String) -> Unit)? = null,
    /** Display version for the drawer header (e.g. "4.0.1 · arm64"); null hides the line. */
    val appVersion: String? = null,
    /** Thumbnail loading; null keeps the placeholder boxes (e.g. a shell without a cache dir yet). */
    val imageLoader: ImageLoader? = null,
    /** The app's root logger (Sentry-backed in the shells); screens fork their own scope off it. */
    val logger: kmpcommon.log.Logger? = null,
    /** App settings (UI language, theme, …); the in-memory default works but doesn't persist. */
    val settings: SettingsStore = InMemorySettingsStore(),
    /**
     * True when [playerSurface] may be shown at any size (desktop MSE blit / ffmpeg frames scale) —
     * the mini player then hosts it as its 36dp art tile. False for a windowed WebView (iOS): the
     * page sizes its streams to the real viewport, so a thumbnail-sized surface kills playback and
     * the UI must keep a real-size docked preview instead.
     */
    val surfaceScalesDown: Boolean = false,
    /**
     * The platform browser WebView for the YouTube sign-in flow, provided by the shell (like
     * [playerSurface]) so the shared UI carries no WebView dependency. Null hosts (headless tests, a
     * shell without a WebView stack) show a "sign-in unavailable" note. It MUST use the platform's
     * default cookie store — the same jar the engine reads — so sign-in cookies reach the login gate.
     */
    val loginBrowser: LoginBrowser? = null,
    /**
     * The offline-download queue service; null on a shell that can't download (no WebView stack, or a
     * headless test graph) — the Downloads screen then shows an unavailable note. Built once per shell,
     * like [mediaStore].
     */
    val downloads: io.ymusic.download.DownloadService? = null,
    /**
     * Delivers a user feedback report to the crash-reporting backend (Sentry user feedback in the
     * shells). Null hides Settings' "Send feedback" entry entirely — e.g. a build with no DSN, or a
     * platform without the telemetry module.
     */
    val sendFeedback: (suspend (FeedbackDraft) -> Unit)? = null,
    /**
     * The OS file picker for feedback attachments, reading each picked file's bytes (an over-limit
     * file comes back with null bytes — see [FeedbackFile]). Null hides the attach button while the
     * rest of the feedback form still works.
     */
    val pickFeedbackFiles: (suspend () -> List<FeedbackFile>)? = null,
    /**
     * The shell's self-updater; null where the platform store owns updates (mobile) or the shell
     * has none — the update prompt and its setting then never appear.
     */
    val updates: AppUpdates? = null,
    /**
     * Anonymous usage stats — `stats?.record("play")` from any screen. The consent gate and the
     * no-service case are its own business ([StatUsage.record] returns before doing any work);
     * null only in shells and tests that wire no recorder at all.
     */
    val stats: StatUsage? = null,
    /**
     * Desktop: true while the top-level window is being resized. The shell sets this from
     * `componentResized` and clears it after 300ms without another event. Unused on mobile.
     */
    val windowResizing: MutableStateFlow<Boolean> = MutableStateFlow(false),
    // Kept last so shells pass it as the trailing lambda.
    val playerSurface: @Composable (modifier: Modifier) -> Unit,
) {
    /**
     * Bumped after a locale pick landed in the engine (SettingsScreen). The content locale itself
     * LIVES in the engine — the app only hears "it changed" and throws locale-shaped caches away.
     */
    val pip = PipCoordinator(player, settings)

    /** Fullscreen only: stretch the picture to the hole. Mini / Now Playing always fit. */
    val videoFillScreen = mutableStateOf(false)

    val localeEpoch = MutableStateFlow(0)

    /** App-wide reload signal (desktop F5/Cmd+R): the visible feed surface refetches. */
    val refreshRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    fun requestRefresh() {
        refreshRequests.tryEmit(Unit)
    }
}
