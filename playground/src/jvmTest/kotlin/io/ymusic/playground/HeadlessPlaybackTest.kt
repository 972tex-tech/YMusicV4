package io.ymusic.playground

import io.ymusic.mse.MseVideoSurface
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class HeadlessPlaybackTest {
    @Test
    fun mse_gets_frames_and_play_pause_seek() = runBlocking {
        if (!youtubeReachable()) {
            println("skip: offline")
            return@runBlocking
        }
        val surface = MseVideoSurface()
        val frames = AtomicInteger(0)
        val frameWatch = launch {
            surface.frame.collect { img -> if (img != null) frames.incrementAndGet() }
        }
        val session = HeadlessSession.boot(surface)
        try {
            session.player.load(VIDEO_ID, session.log, "auto")
            session.player.play(session.log)

            val deadline = System.currentTimeMillis() + 60_000
            var dur = 0L
            while (System.currentTimeMillis() < deadline) {
                val st = session.player.status(session.log)
                dur = (st["durationMs"] as? JsonPrimitive)?.longOrNull ?: 0L
                if (frames.get() >= 2 && dur > 0) break
                delay(250)
            }
            println(
                "frames=${frames.get()} dur=$dur size=${surface.frame.value?.let { "${it.width}x${it.height}" }}",
            )
            assertTrue(frames.get() >= 2, "MSE never published video frames frames=${frames.get()}")

            session.player.play(session.log)
            delay(400)
            val moving = (session.player.status(session.log)["positionMs"] as? JsonPrimitive)?.longOrNull ?: 0L
            delay(1_200)
            val later = (session.player.status(session.log)["positionMs"] as? JsonPrimitive)?.longOrNull ?: 0L
            println("clock $moving -> $later")
            assertTrue(later > moving, "play did not advance clock $moving -> $later")

            session.player.pause(session.log)
            delay(400)
            val pausedAt = (session.player.status(session.log)["positionMs"] as? JsonPrimitive)?.longOrNull ?: 0L
            delay(800)
            val still = (session.player.status(session.log)["positionMs"] as? JsonPrimitive)?.longOrNull ?: 0L
            println("pause $pausedAt -> $still")
            assertTrue(abs(still - pausedAt) < 1_500, "clock kept moving while paused $pausedAt -> $still")

            /* SPS=1 is googlevideo accepting the PO, not playback. Pause-then-seek
               lands currentTime while frozen; play and require the clock to run. */
            val framesBeforeSeek = frames.get()
            session.player.play(session.log)
            delay(200)
            session.player.seekTo(150_000, session.log)
            session.player.play(session.log)
            val seekDeadline = System.currentTimeMillis() + 20_000
            var landed = 0L
            while (System.currentTimeMillis() < seekDeadline) {
                landed = (session.player.status(session.log)["positionMs"] as? JsonPrimitive)?.longOrNull ?: 0L
                if (landed in 145_000..165_000) break
                delay(250)
            }
            println("hole-seek land -> $landed frames=${frames.get()}")
            assertTrue(landed in 145_000..165_000, "seek did not land near 150s landed=$landed")
            val playDeadline = System.currentTimeMillis() + 15_000
            var afterSeek = landed
            while (System.currentTimeMillis() < playDeadline) {
                afterSeek = (session.player.status(session.log)["positionMs"] as? JsonPrimitive)?.longOrNull ?: 0L
                if (afterSeek > landed + 800) break
                delay(250)
            }
            println("hole-seek play $landed -> $afterSeek frames=${frames.get()}")
            assertTrue(afterSeek > landed + 800, "seek did not play $landed -> $afterSeek")
            assertTrue(
                frames.get() > framesBeforeSeek,
                "no new frames after seek frames=${frames.get()} before=$framesBeforeSeek",
            )
            val proof = session.proofFile.takeIf { it.isFile }?.readText() ?: ""
            val minter = proof.lineSequence().firstOrNull {
                it.contains("minter GenerateIT") || it.contains("owl m")
            }
            val spsLines = proof.lineSequence().filter { it.contains("SPS=") }.toList()
            val sps3 = spsLines.any { it.contains("SPS=3") }
            val sps1 = spsLines.any { it.contains("SPS=1") }
            println("po-proof minter=$minter sps1=$sps1 sps3=$sps3 spsLines=${spsLines.size}")
            for (line in spsLines.takeLast(12)) println("sps $line")
            assertTrue(minter != null, "player minter did not run")
            assertTrue(sps1, "no SABR SPS=1 (ok) after hole-seek spsLines=$spsLines")
            assertTrue(!sps3, "SABR SPS=3 po-rejected minter=$minter")
        } finally {
            frameWatch.cancel()
            session.close()
        }
    }

    @Test
    fun quality_then_seek_keeps_playing() = runBlocking {
        if (!youtubeReachable()) {
            println("skip: offline")
            return@runBlocking
        }
        val surface = MseVideoSurface()
        val frames = AtomicInteger(0)
        val frameWatch = launch {
            surface.frame.collect { img -> if (img != null) frames.incrementAndGet() }
        }
        val session = HeadlessSession.boot(surface)
        try {
            session.player.load(VIDEO_ID, session.log, "auto")
            session.player.setViewport(1920, 1080, session.log)
            session.player.play(session.log)
            session.watchPlay(
                frames,
                minDelta = 2_000,
                timeoutMs = 60_000,
                audioStallBudget = STARTUP_AUDIO_STALL_MS,
                label = "first play",
                requireNoJump = false,
            )

            session.switchQuality("hd1080", frames, forced = true, label = "hd1080")
            session.switchQuality("tiny", frames, forced = true, label = "tiny")
            session.switchQuality("auto", frames, forced = false, label = "auto")

            session.player.seekTo(30_000, session.log)
            session.player.play(session.log)
            val land = session.awaitLand(25_000L..40_000L, timeoutMs = 20_000, label = "seek 30s")
            session.watchPlay(
                frames,
                from = land,
                minDelta = 800,
                timeoutMs = 15_000,
                audioStallBudget = STARTUP_AUDIO_STALL_MS,
                label = "play after seek",
                requireNoJump = false,
            )
            session.switchQuality("hd1080", frames, forced = true, label = "hd1080 after seek")
            dumpMseProof(session, "quality_then_seek")
        } finally {
            frameWatch.cancel()
            session.close()
        }
    }

    @Test
    fun quality_then_second_load_plays() = runBlocking {
        if (!youtubeReachable()) {
            println("skip: offline")
            return@runBlocking
        }
        val surface = MseVideoSurface()
        val frames = AtomicInteger(0)
        val frameWatch = launch {
            surface.frame.collect { img -> if (img != null) frames.incrementAndGet() }
        }
        val session = HeadlessSession.boot(surface)
        try {
            session.player.load(VIDEO_ID, session.log, "auto")
            session.player.setViewport(1920, 1080, session.log)
            session.player.play(session.log)
            session.watchPlay(
                frames,
                minDelta = 2_000,
                timeoutMs = 60_000,
                audioStallBudget = STARTUP_AUDIO_STALL_MS,
                label = "first video",
                requireNoJump = false,
            )
            session.switchQuality("hd1080", frames, forced = true, label = "hd1080")
            session.switchQuality("tiny", frames, forced = true, label = "tiny")

            val proof = session.proofFile.takeIf { it.isFile }?.readText().orEmpty()
            val srcs = proof.lineSequence().filter { it.contains("mse video.src blob:mse/") }.toList()
            println("quality srcs=${srcs.size}")
            for (s in srcs) println("  src $s")

            session.player.load(SECOND_ID, session.log, "auto")
            session.player.play(session.log)
            val land = session.awaitLand(0L..20_000L, timeoutMs = 60_000, label = "second load")
            session.watchPlay(
                frames,
                from = land,
                minDelta = 800,
                timeoutMs = 20_000,
                audioStallBudget = STARTUP_AUDIO_STALL_MS,
                label = "second load play",
                requireNoJump = false,
            )
            dumpMseProof(session, "quality_then_second_load")
        } finally {
            frameWatch.cancel()
            session.close()
        }
    }

    companion object {
        private const val VIDEO_ID = "x72VDoEpTIU"
        private const val SECOND_ID = "wjhyk-Cx6wk"
    }
}

/** First load / seek fill — SABR has nothing buffered yet. */
private const val STARTUP_AUDIO_STALL_MS = 8_000L
/** YouTube forced quality stalls ~0.5s audio+video; leave room for SABR RTT. */
private const val FORCED_AUDIO_STALL_MS = 1_500L
/** Auto keeps playing leftover and switches at the end — no audio gap. */
private const val AUTO_AUDIO_STALL_MS = 400L
/** Video may hitch after a quality change; audio must not wait for it. */
private const val VIDEO_LAG_MS = 5_000L
private const val JUMP_MS = 400L
/** Quality switch may snap to a nearby RAP / keyframe (~6s GOP). 2s→0 is this, not a restart. */
private const val NEARBY_SNAP_MS = 8_000L

private suspend fun HeadlessSession.clockMs(): Long =
    (player.status(log)["positionMs"] as? JsonPrimitive)?.longOrNull ?: 0L

private suspend fun HeadlessSession.switchQuality(
    quality: String,
    frames: AtomicInteger,
    forced: Boolean,
    label: String,
) {
    val before = clockMs()
    player.setQuality(quality, log)
    player.play(log)
    watchPlay(
        frames,
        from = before,
        minDelta = 6_500,
        timeoutMs = 30_000,
        audioStallBudget = if (forced) FORCED_AUDIO_STALL_MS else AUTO_AUDIO_STALL_MS,
        label = label,
        requireNoJump = true,
    )
}

/**
 * Sample the audio clock (~currentTime) and video frames.
 *
 * YouTube: a quality change may snap to a nearby keyframe (at ~2s that
 * nearby point is 0). Forced quality may stall audio+video ~0.5s; auto
 * plays leftover then switches. A jump farther than [NEARBY_SNAP_MS] is
 * a restart, not a snap. Video may lag the clock by up to 5s. JVM blit
 * often publishes 0 frames even when native v_sent runs — only count
 * video lag after we have seen a frame.
 */
private suspend fun HeadlessSession.watchPlay(
    frames: AtomicInteger,
    from: Long? = null,
    minDelta: Long,
    timeoutMs: Long,
    audioStallBudget: Long,
    label: String,
    requireNoJump: Boolean,
): Pair<Long, Long> {
    var start = from ?: clockMs()
    val deadline = System.currentTimeMillis() + timeoutMs
    var lastClock = start
    var lastWall = System.currentTimeMillis()
    var lastFrames = frames.get()
    var seenFrames = lastFrames > 0
    var audioStall = 0L
    var videoStall = 0L
    var maxAudioStall = 0L
    var maxVideoLag = 0L
    var maxJump = 0L
    while (System.currentTimeMillis() < deadline) {
        delay(100)
        val wall = System.currentTimeMillis()
        val now = clockMs()
        val wallDt = wall - lastWall
        if (now + JUMP_MS < lastClock) {
            val jump = lastClock - now
            maxJump = maxOf(maxJump, jump)
            if (jump <= NEARBY_SNAP_MS) {
                println("$label nearby snap $lastClock -> $now")
                start = now
                lastClock = now
                lastWall = wall
                audioStall = 0
                continue
            }
            if (requireNoJump) {
                dumpMseProof(this, "$label JUMP")
                throw AssertionError(
                    "$label audio jumped $lastClock -> $now (farther than ${NEARBY_SNAP_MS}ms snap)",
                )
            }
        }
        val clockDt = now - lastClock
        if (clockDt < 50 && wallDt >= 80) {
            audioStall += wallDt
            maxAudioStall = maxOf(maxAudioStall, audioStall)
        } else {
            audioStall = 0
        }
        val n = frames.get()
        if (n > lastFrames) {
            seenFrames = true
            videoStall = 0
            lastFrames = n
        } else if (seenFrames && clockDt >= 50) {
            videoStall += wallDt
            maxVideoLag = maxOf(maxVideoLag, videoStall)
        }
        lastClock = now
        lastWall = wall
        if (maxAudioStall > audioStallBudget) {
            dumpMseProof(this, "$label AUDIO-STALL")
            throw AssertionError(
                "$label audio stalled ${maxAudioStall}ms (budget ${audioStallBudget}ms) clock=$now",
            )
        }
        if (seenFrames && maxVideoLag > VIDEO_LAG_MS) {
            dumpMseProof(this, "$label VIDEO-LAG")
            throw AssertionError(
                "$label video lagged ${maxVideoLag}ms behind running audio (budget ${VIDEO_LAG_MS}ms) clock=$now frames=$n",
            )
        }
        if (now > start + minDelta) {
            println(
                "$label clock $start -> $now audioStall=${maxAudioStall}ms videoLag=${maxVideoLag}ms " +
                    "jump=${maxJump}ms frames=$n",
            )
            return start to now
        }
    }
    dumpMseProof(this, "$label TIMEOUT")
    throw AssertionError(
        "$label did not advance $start -> $lastClock (need +$minDelta) " +
            "audioStall=${maxAudioStall}ms videoLag=${maxVideoLag}ms frames=${frames.get()}",
    )
}

/** Wait until the clock is in [range]. Returns the landed position. */
private suspend fun HeadlessSession.awaitLand(
    range: LongRange,
    timeoutMs: Long,
    label: String,
): Long {
    val deadline = System.currentTimeMillis() + timeoutMs
    var pos = clockMs()
    while (System.currentTimeMillis() < deadline) {
        pos = clockMs()
        if (pos in range) {
            println("$label land -> $pos")
            return pos
        }
        delay(250)
    }
    throw AssertionError("$label did not land in $range pos=$pos")
}

private fun dumpMseProof(session: HeadlessSession, label: String) {
    val proof = session.proofFile.takeIf { it.isFile }?.readText().orEmpty()
    val keys = listOf(
        "mse video.src",
        "mse leftover",
        "mse append",
        "changeType",
        "__mseReset",
        "__mseChangeType",
        "Error parsing",
        "mse load failed",
        "mse=[",
    )
    println("--- proof $label ---")
    val lines = proof.lineSequence().filter { line -> keys.any { line.contains(it) } }.toList()
    for (line in lines.takeLast(40)) println("  $line")
    println("--- /proof $label n=${lines.size} ---")
}
