package io.ymusic.desktop.setup

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.use
import io.ymusic.ui.YMusicTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/** Dev harness, not an assertion test: renders the first-run states headlessly to PNGs so the
 *  screen can be checked against the design without launching the app. */
class OnboardingSnapshotTest {

    private val out = File("build/onboarding-snapshots").apply { mkdirs() }

    private fun render(name: String, dark: Boolean, state: SetupState, step2: Boolean = false) {
        val scene = ImageComposeScene(width = 520, height = 660) {
            YMusicTheme(dark = dark) {
                OnboardingScreen(
                    state = state,
                    crashReports = true,
                    onCrashReportsChange = {},
                    onRetryEngine = {},
                    onRetryMediaTools = {},
                    onRecheckVlc = {},
                    onChooseFolder = {},
                    onOpenUrl = {},
                    onFinish = {},
                    dark = dark,
                    initialStep = if (step2) 2 else 1,
                )
            }
        }
        scene.use {
            val png = it.render().encodeToData(EncodedImageFormat.PNG)!!.bytes
            File(out, "$name${if (dark) "-dark" else ""}.png").writeBytes(png)
        }
    }

    @Test
    fun renderFirstRunStates() {
        val downloading = SetupState(
            engine = ComponentState.Working(0.58f),
            mediaTools = ComponentState.Working(0.35f),
            vlc = ComponentState.Absent,
        )
        val done = SetupState(
            engine = ComponentState.Ready,
            mediaTools = ComponentState.Ready,
            vlc = ComponentState.Absent,
        )
        // Both, again: the screen carries its own light/dark switch.
        listOf(true, false).forEach { dark ->
            render("downloading", dark, downloading)
            render("complete", dark, done)
            render(
                "failed",
                dark,
                downloading.copy(engine = ComponentState.Failed("Couldn't reach the download server")),
            )
            render("folder-chosen", dark, done.copy(musicFolder = "/Users/you/Music/Library"))
            render("vlc-present", dark, done.copy(vlc = ComponentState.Ready))
            render("consent", dark, done, step2 = true)
        }
    }
}
