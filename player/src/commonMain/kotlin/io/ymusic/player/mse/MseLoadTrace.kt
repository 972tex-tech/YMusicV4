package io.ymusic.player.mse

import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * How far one [MsePlayer.load] got, for the load-timeout log. Milestones are ms since the
 * load was queued; null = never reached. Main-thread only, like [MsePlayer].
 */
internal class MseLoadTrace(
    val videoId: String,
    val audioOnly: Boolean,
    val quality: String?,
    /** Commands still queued ahead of this load on the session's FIFO. */
    val queuedAhead: Int,
    /** How long the command running when this load was queued had been running, if any. */
    val runningCommandMs: Long?,
    /** The native host's cumulative presented-frame count when the load was queued. */
    private val framesAtQueue: Int?,
) {
    private val queuedAt: TimeMark = TimeSource.Monotonic.markNow()

    var commandStartedMs: Long? = null
        private set
    var commandAckedMs: Long? = null
        private set
    var videoDataMs: Long? = null
        private set
    var playerResponseMs: Long? = null
        private set
    var pictureMs: Long? = null
        private set
    var firstStateMs: Long? = null
        private set
    var lastState: String? = null
        private set
    var lastEventMs: Long? = null
        private set
    var otherVideoTicks = 0
        private set
    var lastOtherVideoId: String? = null
        private set

    private fun elapsed(): Long = queuedAt.elapsedNow().inWholeMilliseconds

    fun commandStarted() {
        commandStartedMs = elapsed()
    }

    fun commandAcked() {
        commandAckedMs = elapsed()
    }

    /** A details push for this video; [hasPlayerResponse] = it carried the /player response's videoDetails. */
    fun details(hasPlayerResponse: Boolean) {
        if (videoDataMs == null) videoDataMs = elapsed()
        if (hasPlayerResponse && playerResponseMs == null) playerResponseMs = elapsed()
    }

    fun picture() {
        if (pictureMs == null) pictureMs = elapsed()
    }

    fun state(newState: String) {
        if (firstStateMs == null) firstStateMs = elapsed()
        lastState = newState
    }

    fun progressFor(otherVideoId: String?) {
        otherVideoTicks++
        lastOtherVideoId = otherVideoId
    }

    /** Any listen push from the engine: proves the engine thread was still turning. */
    fun engineEvent() {
        lastEventMs = elapsed()
    }

    /** [runningNowMs]/[queuedNow]: the session FIFO at timeout — a command still running is the engine thread not answering. */
    fun describe(presentedFrames: Int?, runningNowMs: Long?, queuedNow: Int?): String = buildString {
        append("after ").append(elapsed()).append("ms")
        append(" videoId=").append(videoId)
        append(" audioOnly=").append(audioOnly)
        append(" quality=").append(quality ?: "auto")
        append(" queuedAhead=").append(queuedAhead)
        runningCommandMs?.let { append(" runningCommandMs=").append(it) }
        append(" loadSent=").append(ms(commandStartedMs))
        append(" loadAcked=").append(ms(commandAckedMs))
        append(" videoData=").append(ms(videoDataMs))
        append(" playerResponse=").append(ms(playerResponseMs))
        append(" picture=").append(ms(pictureMs))
        append(" firstState=").append(ms(firstStateMs))
        append(" lastState=").append(lastState ?: "-")
        append(" otherVideoTicks=").append(otherVideoTicks)
        lastOtherVideoId?.let { append(" lastOtherVideoId=").append(it) }
        append(" lastEngineEvent=").append(ms(lastEventMs))
        append(" runningNow=").append(ms(runningNowMs))
        append(" queuedNow=").append(queuedNow?.toString() ?: "-")
        val framesDelta = if (presentedFrames != null && framesAtQueue != null) presentedFrames - framesAtQueue else null
        append(" framesPresented=").append(framesDelta?.toString() ?: "-")
    }

    private fun ms(value: Long?): String = if (value == null) "-" else "${value}ms"
}
