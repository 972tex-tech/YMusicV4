package io.ymusic.ios

import io.ymusic.player.Player
import io.ymusic.player.isPlaying
import io.ymusic.ui.PipCoordinator
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVSampleBufferDisplayLayer

internal object IosPipHost {
    var hole: IosMseHole? = null
    var pip: PipCoordinator? = null
    var player: Player? = null
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
