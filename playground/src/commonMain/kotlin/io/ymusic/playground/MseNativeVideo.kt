package io.ymusic.playground

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.ymusic.dynamicengine.player.DynamicEnginePlayer
import io.ymusic.mse.MseVideoSurface
import kotlin.concurrent.Volatile

/**
 * Latest-frame counters the Compose chrome polls (~250ms). The picture itself is
 * painted by the platform hole (Swing / UIKit), not by Compose state.
 */
internal class MseVideoSink {
    @Volatile var frameCount: Int = 0

    @Volatile var frameW: Int = 0

    @Volatile var frameH: Int = 0

    /**
     * Native present target is attached (Android TextureView / iOS
     * AVSampleBufferDisplayLayer). JVM blit has no window, so it stays true.
     */
    @Volatile var surfaceReady: Boolean = true
}

/**
 * Native interop region that blits BGRA off the Compose snapshot.
 * Pointers stay in Compose (JVM: [contains] misses; iOS: `isInteractive = false`).
 */
@Composable
internal expect fun MseNativeVideo(
    surface: MseVideoSurface,
    player: DynamicEnginePlayer?,
    sink: MseVideoSink,
    placeholder: String,
    modifier: Modifier = Modifier,
    /** Coded picture size from listen/player/status (webview `_aspect_ratio`). 0 = unknown. */
    pictureWidth: Int = 0,
    pictureHeight: Int = 0,
)
