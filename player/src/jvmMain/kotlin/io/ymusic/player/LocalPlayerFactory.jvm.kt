package io.ymusic.player

import kmpcommon.error.TechnicalException

/**
 * [videoSurface] is the off-screen frame endpoint the shell renders as a Compose `Image`: libav
 * decodes each frame into it. [ffmpegPath] is unused (in-process libav); kept so the shell can
 * still pass the packed CLI for metadata/mux.
 */
actual class LocalPlayerContext(
    val videoSurface: LocalVideoSurface? = null,
    val ffmpegPath: String? = null,
)

actual fun createLocalPlayer(context: LocalPlayerContext): LocalPlayer {
    return try {
        JvmLocalPlayer(context.videoSurface ?: LocalVideoSurface())
    } catch (t: Throwable) {
        throw TechnicalException("E60020", t.message ?: "libav local player failed")
    }
}
