package io.ymusic.desktop.setup

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import io.ymusic.desktop.groundColorFor
import io.ymusic.desktop.paintWindowGround
import io.ymusic.settings.AppSettings
import io.ymusic.settings.SettingsStore
import io.ymusic.settings.flow
import io.ymusic.settings.put
import io.ymusic.ui.YMusicTheme
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.ymusic_logo
import kmpcommon.log.Logger
import kmphttp.KmpHttpClient
import kmphttp.RedirectOptions
import kmphttp.create
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import java.io.File

/**
 * The first-run window: its own window, sized for the checklist, that must be finished before the
 * app's own window ever opens. Nothing here touches the app graph — the components it installs are
 * exactly what building that graph would otherwise fail on.
 */
@Composable
fun ApplicationScope.FirstRunWindow(
    settings: SettingsStore,
    dataDir: File,
    logger: Logger,
    toolsManifestUrl: String,
    publicKey: String,
    /** An ffmpeg already on this machine — checked before anything is downloaded. */
    existingFfmpeg: () -> File?,
    /** Opens the OS folder chooser; null when the user backed out. */
    pickFolder: suspend () -> String?,
    onOpenUrl: (String) -> Unit,
    onFinished: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val coordinator = remember {
        SetupCoordinator(
            scope = scope,
            ffmpeg = FfmpegProvisioner(
                client = KmpHttpClient.create {
                    this.logger = logger.fork("kmphttp")
                    // Release assets redirect to a CDN, so the download only works if we follow.
                    defaultRedirectOptions = RedirectOptions(followRedirects = true, followSslRedirects = true)
                },
                manifestUrl = toolsManifestUrl,
                publicKey = publicKey,
                toolsDir = File(dataDir, "tools/ffmpeg"),
                platformKey = io.ymusic.desktop.update.currentPlatformKey(),
            ),
            existingFfmpeg = existingFfmpeg,
            pickFolder = pickFolder,
            logger = logger,
        )
    }
    LaunchedEffect(coordinator) { coordinator.start() }

    val state by coordinator.state.collectAsState()
    // Answered before it is stored: nothing is sent until Continue commits the whole step.
    val crashReports = remember { androidx.compose.runtime.mutableStateOf(true) }
    val stats = remember { androidx.compose.runtime.mutableStateOf(true) }
    // Same for the appearance — the switch drives this window immediately, and Continue is what
    // turns it into the app's theme setting. Starts from whatever the setting already says, which
    // on a fresh install is its dark default.
    val dark = remember { androidx.compose.runtime.mutableStateOf(settings.flow(AppSettings.theme).value != "light") }

    val windowState = rememberWindowState(
        size = DpSize(FIRST_RUN_WIDTH, FIRST_RUN_HEIGHT),
        position = WindowPosition.Aligned(Alignment.Center),
    )
    Window(
        onCloseRequest = ::exitApplication,
        title = "YMusic",
        icon = painterResource(Res.drawable.ymusic_logo),
        state = windowState,
        resizable = false,
    ) {
        // Before Compose draws: otherwise the frame shows the toolkit's white for one beat, which on
        // a dark first run is the first thing anyone sees.
        paintWindowGround(groundColorFor(dark.value))
        YMusicTheme(dark = dark.value) {
            OnboardingScreen(
                state = state,
                crashReports = crashReports.value,
                onCrashReportsChange = { crashReports.value = it },
                stats = stats.value,
                onStatsChange = { stats.value = it },
                onRetryMediaTools = coordinator::retryMediaTools,
                onRecheckVlc = coordinator::recheckVlc,
                onChooseFolder = coordinator::chooseMusicFolder,
                onOpenUrl = onOpenUrl,
                dark = dark.value,
                onDarkChange = { dark.value = it },
                onFinish = {
                    scope.launch {
                        // The folder is handed to the app as pending work — scanning belongs to the
                        // Library screen, which can show it, not to a window that is closing.
                        state.musicFolder?.let { settings.put(AppSettings.pendingLibraryFolder, it) }
                        settings.put(AppSettings.crashReports, crashReports.value)
                        settings.put(AppSettings.stats, stats.value)
                        settings.put(AppSettings.theme, if (dark.value) "dark" else "light")
                        settings.put(AppSettings.setupCompleted, true)
                        onFinished()
                    }
                },
            )
        }
    }
}

/** Sized to the checklist: wide enough for a command chip beside its Copy button, tall enough that
 *  the gate's footer sits below the optional rows without scrolling. */
private val FIRST_RUN_WIDTH = 520.dp
private val FIRST_RUN_HEIGHT = 700.dp
