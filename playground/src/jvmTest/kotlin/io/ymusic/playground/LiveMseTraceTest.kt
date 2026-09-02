package io.ymusic.playground

import io.ymusic.mse.MseVideoSurface
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertTrue

class LiveMseTraceTest {
    @Test
    fun live_creates_mediasource() = runBlocking {
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
            session.player.init(session.log)
            session.player.load(LIVE_ID, session.log)
            session.player.play(session.log)
            val deadline = System.currentTimeMillis() + 60_000
            while (System.currentTimeMillis() < deadline) {
                val proof = session.proofFile.takeIf { it.isFile }?.readText().orEmpty()
                if (Regex("""v:[0-9]""").containsMatchIn(proof) && frames.get() >= 2) break
                delay(500)
            }
            val moving = (session.player.status(session.log)["positionMs"] as? JsonPrimitive)?.longOrNull ?: 0L
            delay(1_200)
            val later = (session.player.status(session.log)["positionMs"] as? JsonPrimitive)?.longOrNull ?: 0L
            println("live clock $moving -> $later frames=${frames.get()}")
            val proof = session.proofFile.takeIf { it.isFile }?.readText().orEmpty()
            val traces = proof.lineSequence().filter {
                it.contains("mse MediaSource") ||
                    it.contains("mse video.src") ||
                    it.contains("mse sourceopen") ||
                    it.contains("mse append") ||
                    it.contains("mse createObjectURL") ||
                    it.contains("player RSP") ||
                    it.contains("mse=[")
            }.toList()
            println("live traces (${traces.size}) frames=${frames.get()}:")
            for (t in traces) println("  $t")
            assertTrue(traces.any { it.contains("mse MediaSource create") }, "no MediaSource create. traces=$traces")
            assertTrue(traces.any { it.contains("mse sourceopen") }, "sourceopen never fired. traces=$traces")
            assertTrue(traces.any { it.contains("mse append") }, "no mse append. traces=$traces")
            assertTrue(
                traces.any { it.contains("mse=[") && Regex("""v:[0-9]""").containsMatchIn(it) },
                "video leftover never buffered. traces=$traces",
            )
            assertTrue(frames.get() >= 2, "no video frames (black). frames=${frames.get()}")
            assertTrue(later > moving, "playhead stuck $moving -> $later frames=${frames.get()}")
        } finally {
            frameWatch.cancel()
            session.close()
        }
    }

    companion object {
        private const val LIVE_ID = "hEeAXerJ5n0"
    }
}
