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
            session.player.load(LIVE_ID, session.log, "auto")
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

    @Test
    fun live_audio_only_plays() = runBlocking {
        if (!youtubeReachable()) {
            println("skip: offline")
            return@runBlocking
        }
        val surface = MseVideoSurface()
        val frames = AtomicInteger(0)
        val watch = launch {
            surface.frame.collect { img -> if (img != null) frames.incrementAndGet() }
        }
        val session = HeadlessSession.boot(surface)
        try {
            session.player.load(AUDIO_LIVE_ID, session.log, audioOnly = true)
            session.player.play(session.log)
            val deadline = System.currentTimeMillis() + 90_000
            var pos = 0L
            while (System.currentTimeMillis() < deadline) {
                pos = (session.player.status(session.log)["positionMs"] as? JsonPrimitive)?.longOrNull ?: 0L
                /* Live join lands at DVR head (thousands of seconds), not t=0. */
                if (pos > 10_000) break
                delay(400)
            }
            delay(30_000)
            val later = (session.player.status(session.log)["positionMs"] as? JsonPrimitive)?.longOrNull ?: 0L
            val proof = session.proofFile.takeIf { it.isFile }?.readText().orEmpty()
            val itags = Regex("""qe=[^ ]+ itag=([0-9]+)""").findAll(proof).map { it.value }.toList()
            println("live audio-only clock $pos -> $later frames=${frames.get()}")
            for (t in itags.takeLast(8)) println("  $t")
            assertTrue(!proof.contains("fmt.noneavailable"), "live rejected the video ladder")
            assertTrue(!proof.contains("mse ignore seek-0"), "dummy still forced a seek-0")
            assertTrue(pos > 10_000, "live audio-only never joined DVR head pos=$pos")
            assertTrue(later > pos + 25_000, "live audio-only clock stuck $pos -> $later (need 30s)")
            assertTrue(frames.get() <= 1, "live audio-only decoded video frames=${frames.get()}")
            val abr = proof.lineSequence().filter { it.contains("sabr sabr=") }.toList()
            assertTrue(abr.any { it.contains("40=1") }, "live zero SABR missing field 40=1. abr=$abr")
            assertTrue(
                proof.contains("mse sabr fake-ump"),
                "live zero did not inject fake 144p UMP. proof=\n$proof",
            )
            assertTrue(
                proof.contains("mse append fake-cover"),
                "live fake 144p leftover cover was not appended. proof=\n$proof",
            )
        } finally {
            watch.cancel()
            session.close()
        }
    }

    @Test
    fun live_switch_to_zero_keeps_clock() = runBlocking {
        if (!youtubeReachable()) {
            println("skip: offline")
            return@runBlocking
        }
        val surface = MseVideoSurface()
        val frames = AtomicInteger(0)
        val watch = launch {
            surface.frame.collect { img -> if (img != null) frames.incrementAndGet() }
        }
        val session = HeadlessSession.boot(surface)
        try {
            session.player.load(AUDIO_LIVE_ID, session.log, "auto")
            session.player.play(session.log)
            val deadline = System.currentTimeMillis() + 60_000
            var pos = 0L
            while (System.currentTimeMillis() < deadline) {
                pos = (session.player.status(session.log)["positionMs"] as? JsonPrimitive)?.longOrNull ?: 0L
                if (pos > 10_000) break
                delay(400)
            }
            assertTrue(pos > 10_000, "live auto never joined DVR head pos=$pos")
            delay(800)
            val framesAtSwitch = frames.get()
            session.player.setQuality("auto", true, session.log)
            delay(500)
            val later = (session.player.status(session.log)["positionMs"] as? JsonPrimitive)?.longOrNull ?: 0L
            val samplesAtSwitch = surface.samplesPlayed()
            /* Leftover audio at switch is a few seconds. 30s of clock after
               zero is past that (log12/log23 je=0 then freeze, SABR audio
               never reached appendBuffer). Clock moving is not playback. */
            delay(30_000)
            val after = (session.player.status(session.log)["positionMs"] as? JsonPrimitive)?.longOrNull ?: 0L
            val samplesAfter = surface.samplesPlayed()
            val proof = session.proofFile.takeIf { it.isFile }?.readText().orEmpty()
            println(
                "live switch-zero $later -> $after frames $framesAtSwitch -> ${frames.get()} " +
                    "samples $samplesAtSwitch -> $samplesAfter",
            )
            assertTrue(!proof.contains("mse ignore seek-0"), "switch to zero seek-0")
            assertTrue(after > later + 25_000, "clock stuck after zero leftover $later -> $after (need 30s)")
            assertTrue(
                samplesAfter > samplesAtSwitch,
                "audio not running after zero samples=$samplesAfter at-switch=$samplesAtSwitch clock $later -> $after",
            )
            val afterZero = proof.lineSequence().dropWhile { !it.contains("setQuality zero") }.toList()
            assertTrue(
                afterZero.any { it.contains("40=1") },
                "live zero SABR missing field 40=1",
            )
            assertTrue(
                afterZero.any { it.contains("mse sabr fake-ump") },
                "live zero did not inject fake 144p UMP",
            )
        } finally {
            watch.cancel()
            session.close()
        }
    }

    @Test
    fun live_second_video_audio_only_plays() = runBlocking {
        if (!youtubeReachable()) {
            println("skip: offline")
            return@runBlocking
        }
        val surface = MseVideoSurface()
        val frames = AtomicInteger(0)
        val watch = launch {
            surface.frame.collect { img -> if (img != null) frames.incrementAndGet() }
        }
        val session = HeadlessSession.boot(surface)
        try {
            session.player.load(AUDIO_LIVE_ID, session.log, audioOnly = true)
            session.player.play(session.log)
            val d1 = System.currentTimeMillis() + 60_000
            var pos = 0L
            while (System.currentTimeMillis() < d1) {
                pos = (session.player.status(session.log)["positionMs"] as? JsonPrimitive)?.longOrNull ?: 0L
                if (pos > 10_000) break
                delay(400)
            }
            assertTrue(pos > 10_000, "first live never joined DVR head pos=$pos")
            session.player.load(SECOND_LIVE_ID, session.log, audioOnly = true)
            session.player.play(session.log)
            val d2 = System.currentTimeMillis() + 90_000
            pos = 0L
            var hasAudio = false
            while (System.currentTimeMillis() < d2) {
                pos = (session.player.status(session.log)["positionMs"] as? JsonPrimitive)?.longOrNull ?: 0L
                val proofNow = session.proofFile.takeIf { it.isFile }?.readText().orEmpty()
                /* First live leftover starts ~5.9e6 ms. Second (BEtsctac5KQ) ~1.6e7.
                   Dummy cover moves the clock with buf=[] — require C leftover. */
                hasAudio = proofNow.lineSequence().any { line ->
                    if (!line.contains("mse append a=1") && !line.contains("mse=[")) return@any false
                    val m = Regex("""(?:mse=\[a:|buf=)([0-9]+)""").find(line) ?: return@any false
                    m.groupValues[1].toLong() > 10_000_000L
                }
                if (hasAudio && pos > 10_000) break
                delay(400)
            }
            assertTrue(hasAudio, "second live C audio empty (log14) pos=$pos")
            assertTrue(pos > 10_000, "second live never joined DVR head pos=$pos")
            delay(30_000)
            val later = (session.player.status(session.log)["positionMs"] as? JsonPrimitive)?.longOrNull ?: 0L
            println("live second-video $pos -> $later audio=$hasAudio frames=${frames.get()}")
            assertTrue(later > pos + 25_000, "no sound after second live $pos -> $later")
            assertTrue(frames.get() <= 2, "second live decoded video frames=${frames.get()}")
        } finally {
            watch.cancel()
            session.close()
        }
    }

    companion object {
        private const val LIVE_ID = "hEeAXerJ5n0"
        private const val AUDIO_LIVE_ID = "js6WuvtC-kc"
        private const val SECOND_LIVE_ID = "BEtsctac5KQ"
    }
}
