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
            session.player.load(VIDEO_ID, session.log)
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

            session.player.seekTo(150_000, session.log)
            delay(4_000)
            val afterSeek = (session.player.status(session.log)["positionMs"] as? JsonPrimitive)?.longOrNull ?: 0L
            println("hole-seek -> $afterSeek")
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

    companion object {
        private const val VIDEO_ID = "x72VDoEpTIU"
    }
}
