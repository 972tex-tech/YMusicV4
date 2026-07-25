package ymusic.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import ymusic.dynamicengine.yt.YtContext
import ymusic.settings.AppSettings
import ymusic.settings.flow

/** The device's own content language + region, e.g. `("vi", "VN")` — the seed a fresh install uses. */
internal expect fun deviceContentLocale(): Pair<String, String>

/**
 * Resolve `hl`/`gl` for every InnerTube call. Two independent ladders:
 *
 * - language: the user's pick, else the device language. The device is the better default than
 *   anything YouTube reports, which only echoes the `Accept-Language` we sent it.
 * - region: the user's pick, else what YouTube itself reports for this connection (IP-derived, so a
 *   Vietnamese user on an English device gets Vietnamese charts), else the device region.
 */
internal fun resolveYtContext(
    hl: String,
    gl: String,
    glDetected: String,
    device: Pair<String, String> = deviceContentLocale(),
): YtContext = YtContext(
    hl = hl.ifBlank { device.first },
    gl = (gl.ifBlank { glDetected }).ifBlank { device.second }.uppercase(),
)

/** [resolveYtContext] over the live settings — recomposes when the user changes either picker. */
@Composable
internal fun rememberYtContext(graph: AppGraph): YtContext {
    val hl by graph.settings.flow(AppSettings.hl).collectAsState()
    val gl by graph.settings.flow(AppSettings.gl).collectAsState()
    val detected by graph.settings.flow(AppSettings.glDetected).collectAsState()
    return resolveYtContext(hl, gl, detected)
}
