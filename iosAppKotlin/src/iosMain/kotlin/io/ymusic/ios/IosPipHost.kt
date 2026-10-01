package io.ymusic.ios

import io.ymusic.player.Player
import io.ymusic.player.Progress
import io.ymusic.player.isPlaying
import io.ymusic.player.mse.MsePlayer
import io.ymusic.ui.PipCoordinator
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVSampleBufferDisplayLayer

internal object IosPipHost {
    var hole: IosMseHole? = null
    var pip: PipCoordinator? = null
    var player: Player? = null
    var mse: MsePlayer? = null
}

@OptIn(ExperimentalForeignApi::class)
fun iosPipDisplayLayer(): AVSampleBufferDisplayLayer? = IosPipHost.hole?.view?.vtLayer

fun iosPipShouldAutoEnter(): Boolean = IosPipHost.pip?.autoEnterEligible() == true

fun iosOnLeaveWithoutPip() {
    IosPipHost.pip?.onLeaveWithoutPip()
}

fun iosOnPipStarted() {
    IosPipHost.pip?.onPipStarted()
}

fun iosOnPipStopped(appInForeground: Boolean) {
    IosPipHost.pip?.onPipStopped(appInForeground)
}

fun iosOnEnterForeground() {
    IosPipHost.pip?.onEnterForeground()
}

fun iosPipPlay() {
    IosPipHost.player?.play()
}

fun iosPipPause() {
    IosPipHost.player?.pause()
}

fun iosPipIsPlaying(): Boolean =
    IosPipHost.player?.status?.value?.state?.isPlaying() == true

/** Seconds of media duration, or 0 when live/unknown (Swift maps 0 to an infinite PiP range). */
fun iosPipDurationSeconds(): Double {
    val p = IosPipHost.player?.progress?.value ?: return 0.0
    if (p.isLive && !p.seekable) return 0.0
    val ms = p.durationMs
    return if (ms > 0L && ms != Progress.DURATION_UNKNOWN) ms / 1000.0 else 0.0
}

fun iosPipSeekBySeconds(delta: Double) {
    val player = IosPipHost.player ?: return
    val pos = player.progress.value.positionMs
    player.seekTo((pos + (delta * 1000.0).toLong()).coerceAtLeast(0L))
}

fun iosPipSetRenderSize(width: Int, height: Int) {
    if (width < 2 || height < 2) return
    IosPipHost.mse?.setViewport(width, height)
}
