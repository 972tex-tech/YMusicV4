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
        val session = HeadlessSession.boot(MseVideoSurface())
        try {
            session.player.load(VIDEO_ID, session.log, "auto")
            session.player.setViewport(1920, 1080, session.log)
            session.player.play(session.log)
            session.awaitAdvance(minPos = 2_000, minDelta = 800, timeoutMs = 60_000, label = "first play")

            session.player.setQuality("hd1080", session.log)
            session.player.play(session.log)
            session.awaitAdvance(minDelta = 6_500, timeoutMs = 30_000, stallMs = 8_000, label = "after hd1080")

            session.player.setQuality("tiny", session.log)
            session.player.play(session.log)
            session.awaitAdvance(minDelta = 6_500, timeoutMs = 30_000, stallMs = 8_000, label = "after tiny")

            session.player.seekTo(30_000, session.log)
            session.player.play(session.log)
            val land = session.awaitLand(25_000L..40_000L, timeoutMs = 20_000, label = "seek 30s")
            session.awaitAdvance(from = land, minDelta = 800, timeoutMs = 15_000, label = "play after seek")

            session.player.setQuality("hd1080", session.log)
            session.player.play(session.log)
            session.awaitAdvance(minDelta = 6_500, timeoutMs = 30_000, stallMs = 8_000, label = "hd1080 after seek")

            dumpMseProof(session, "quality_then_seek")
        } finally {
            session.close()
        }
    }

    @Test
    fun quality_then_second_load_plays() = runBlocking {
        if (!youtubeReachable()) {
            println("skip: offline")
            return@runBlocking
        }
        val session = HeadlessSession.boot(MseVideoSurface())
        try {
            session.player.load(VIDEO_ID, session.log, "auto")
            session.player.setViewport(1920, 1080, session.log)
            session.player.play(session.log)
            session.awaitAdvance(minPos = 2_000, minDelta = 800, timeoutMs = 60_000, label = "first video")

            session.player.setQuality("hd1080", session.log)
            session.player.play(session.log)
            session.awaitAdvance(minDelta = 6_500, timeoutMs = 30_000, stallMs = 8_000, label = "after hd1080")
            session.player.setQuality("tiny", session.log)
            session.player.play(session.log)
            session.awaitAdvance(minDelta = 6_500, timeoutMs = 30_000, stallMs = 8_000, label = "after tiny")

            val proof = session.proofFile.takeIf { it.isFile }?.readText().orEmpty()
            val srcs = proof.lineSequence().filter { it.contains("mse video.src blob:mse/") }.toList()
            println("quality srcs=${srcs.size}")
            for (s in srcs) println("  src $s")
            if (srcs.size >= 2) {
                val after = proof.substringAfter(srcs.last())
                val leftoverStuck = after.contains("120240+") || after.contains("90180+")
                assertTrue(!leftoverStuck, "new MediaSource still reporting previous ×5 leftover")
            }

            session.player.load(SECOND_ID, session.log, "auto")
            session.player.play(session.log)
            val land = session.awaitLand(0L..20_000L, timeoutMs = 60_000, label = "second load")
            session.awaitAdvance(from = land, minDelta = 800, timeoutMs = 20_000, label = "second load play")
            dumpMseProof(session, "quality_then_second_load")
        } finally {
            session.close()
        }
    }

    companion object {
        private const val VIDEO_ID = "x72VDoEpTIU"
        private const val SECOND_ID = "wjhyk-Cx6wk"
    }
}

private suspend fun HeadlessSession.clockMs(): Long =
    (player.status(log)["positionMs"] as? JsonPrimitive)?.longOrNull ?: 0L

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

/**
 * Wait for the playhead to move. [minPos] is an absolute floor; [minDelta] is
 * relative to [from] (or the position at call time). Fail if the clock freezes
 * for [stallMs] before we hit the target.
 */
private suspend fun HeadlessSession.awaitAdvance(
    from: Long? = null,
    minPos: Long = 0,
    minDelta: Long = 800,
    timeoutMs: Long,
    stallMs: Long = 8_000,
    label: String,
): Pair<Long, Long> {
    var start = from ?: clockMs()
    val deadline = System.currentTimeMillis() + timeoutMs
    var lastMoveAt = System.currentTimeMillis()
    var last = start
    while (System.currentTimeMillis() < deadline) {
        delay(250)
        val now = clockMs()
        if (now + 500 < last) {
            /* New MediaSource / load() resets currentTime to 0. Restart the window. */
            println("$label clock reset $last -> $now")
            start = now
            last = now
            lastMoveAt = System.currentTimeMillis()
            continue
        }
        if (now > last + 200) lastMoveAt = System.currentTimeMillis()
        last = now
        if (now >= minPos && now > start + minDelta) {
            println("$label clock $start -> $now")
            return start to now
        }
        if (System.currentTimeMillis() - lastMoveAt > stallMs) {
            dumpMseProof(this, "$label FROZEN")
            throw AssertionError("$label clock frozen at ${now}ms (from $start)")
        }
    }
    dumpMseProof(this, "$label TIMEOUT")
    throw AssertionError("$label did not advance $start -> $last (need +$minDelta, minPos=$minPos)")
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
