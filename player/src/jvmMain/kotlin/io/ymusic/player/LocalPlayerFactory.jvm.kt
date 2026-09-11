package io.ymusic.player

import kmpcommon.error.TechnicalException
import java.io.File

/**
 * [videoSurface] is the off-screen frame endpoint the shell renders as a Compose `Image`: ffmpeg
 * decodes each frame into it and the engine owns its visibility. [ffmpegPath] is the packed
 * `ffmpeg` binary. When omitted, the factory creates a surface so the picture is always reachable
 * via [LocalPlayer.localVideoSurface].
 */
actual class LocalPlayerContext(
    val videoSurface: LocalVideoSurface? = null,
    val ffmpegPath: String? = null,
)

actual fun createLocalPlayer(context: LocalPlayerContext): LocalPlayer {
    val ffmpeg = context.ffmpegPath?.takeIf { File(it).canExecute() }
        ?: throw TechnicalException("E60020", "ffmpeg not found")
    return JvmLocalPlayer(context.videoSurface ?: LocalVideoSurface(), ffmpeg)
}
