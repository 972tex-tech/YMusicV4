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

class AudioOnlySabrTest {
    @Test
    fun load_zero_sabr_is_audio_only_no_video_frames() = runBlocking {
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
            session.player.load(VIDEO_ID, session.log, "zero")
            session.player.play(session.log)
            val deadline = System.currentTimeMillis() + 90_000
            var pos = 0L
            var lastMoveAt = System.currentTimeMillis()
            while (System.currentTimeMillis() < deadline) {
                val st = session.player.status(session.log)
                val next = (st["positionMs"] as? JsonPrimitive)?.longOrNull ?: 0L
                if (next > pos + 200) lastMoveAt = System.currentTimeMillis()
                pos = next
                val proof = session.proofFile.takeIf { it.isFile }?.readText().orEmpty()
                if (pos >= 8_000 && proof.contains("mse append a=1")) break
                if (pos in 500 until 8_000 && System.currentTimeMillis() - lastMoveAt > 5_000) {
                    throw AssertionError("clock frozen at ${pos}ms")
                }
                delay(400)
            }
            delay(1_500)
            val later = (session.player.status(session.log)["positionMs"] as? JsonPrimitive)?.longOrNull ?: 0L
            val proof = session.proofFile.takeIf { it.isFile }?.readText().orEmpty()
            val abr = proof.lineSequence().filter { it.contains("sabr sabr=") }.toList()
            println("audio-only pos $pos -> $later frames=${frames.get()}")
            for (l in abr.takeLast(8)) println("  $l")
            assertTrue(later >= 8_000 || pos >= 8_000, "audio playhead stuck $pos -> $later")
            assertTrue(abr.isNotEmpty(), "no sabr request logs. proof=\n$proof")
            assertTrue(
                abr.any { it.contains("40=1") },
                "VOD zero SABR missing field 40=1. abr=$abr",
            )
            assertTrue(
                !proof.contains("mse dummy SB"),
                "dummy video SourceBuffer still in use. proof=\n$proof",
            )
            assertTrue(
                proof.contains("mse append a=1"),
                "audio never appended. proof=\n$proof",
            )
            assertTrue(
                proof.contains("mse sabr fake-ump"),
                "VOD zero did not inject fake 144p UMP. proof=\n$proof",
            )
            assertTrue(
                proof.contains("mse append fake-cover"),
                "YouTube never appendBuffer'd the fake 144p. proof=\n$proof",
            )
            assertTrue(
                !proof.contains("mse append fake-rest"),
                "fake-rest still skips appendBuffer. proof=\n$proof",
            )
            println(
                "aud/vid: " +
                    Regex("""aud=([^ ]+) vid=([^ ]+)""").findAll(abr.joinToString("\n"))
                        .map { it.value }.toList(),
            )
            assertNo144pOnSabr(abr)
            assertTrue(frames.get() == 0, "video was rendered/decoded frames=${frames.get()}")

            session.player.seekTo(150_000, session.log)
            session.player.play(session.log)
            val seekDeadline = System.currentTimeMillis() + 30_000
            var seekPos = 0L
            while (System.currentTimeMillis() < seekDeadline) {
                seekPos = (session.player.status(session.log)["positionMs"] as? JsonPrimitive)?.longOrNull ?: 0L
                if (seekPos in 145_000..180_000) break
                delay(400)
            }
            println("audio-only hole-seek land -> $seekPos")
            assertTrue(seekPos in 145_000..180_000, "audio-only seek did not land near 150s $seekPos")
            val playDeadline = System.currentTimeMillis() + 15_000
            var seekLater = seekPos
            while (System.currentTimeMillis() < playDeadline) {
                seekLater = (session.player.status(session.log)["positionMs"] as? JsonPrimitive)?.longOrNull ?: 0L
                if (seekLater > seekPos + 800) break
                delay(400)
            }
            println("audio-only hole-seek play $seekPos -> $seekLater frames=${frames.get()}")
            assertTrue(seekLater > seekPos + 800, "audio-only seek did not play $seekPos -> $seekLater")
            assertTrue(frames.get() == 0, "video was rendered after audio-only seek frames=${frames.get()}")
        } finally {
            watch.cancel()
            session.close()
        }
    }

    @Test
    fun load_zero_fake_format_appends_and_covers() = runBlocking {
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
            session.player.load(INIT_VIDEO_ID, session.log, "zero")
            session.player.play(session.log)
            val deadline = System.currentTimeMillis() + 90_000
            var pos = 0L
            var lastMoveAt = System.currentTimeMillis()
            var proof = ""
            while (System.currentTimeMillis() < deadline) {
                val st = session.player.status(session.log)
                val next = (st["positionMs"] as? JsonPrimitive)?.longOrNull ?: 0L
                if (next > pos + 200) lastMoveAt = System.currentTimeMillis()
                pos = next
                proof = session.proofFile.takeIf { it.isFile }?.readText().orEmpty()
                val covered = proof.lineSequence().any { videoLeftoverCovers(it, pos) }
                if (proof.contains("mse append fake-cover a=0 kind=1") &&
                    proof.contains("mse append a=1") &&
                    covered &&
                    pos >= 3_000
                ) {
                    break
                }
                if (pos in 500 until 8_000 && System.currentTimeMillis() - lastMoveAt > 5_000) {
                    dumpFakeInitProof(proof, pos)
                    throw AssertionError("clock frozen at ${pos}ms")
                }
                delay(400)
            }
            proof = session.proofFile.takeIf { it.isFile }?.readText().orEmpty()
            dumpFakeInitProof(proof, pos)
            val later = (session.player.status(session.log)["positionMs"] as? JsonPrimitive)?.longOrNull ?: 0L
            assertTrue(later >= 3_000 || pos >= 3_000, "audio playhead stuck $pos -> $later")
            assertTrue(
                proof.contains("mse append fake-cover a=0 kind=0"),
                "fake init never reached appendBuffer. proof=\n$proof",
            )
            assertTrue(
                proof.contains("mse append fake-cover a=0 kind=1"),
                "fake media never reached appendBuffer. proof=\n$proof",
            )
            assertTrue(
                proof.lineSequence().any { videoLeftoverCovers(it, pos) },
                "video leftover does not cover clock=${pos}ms. proof=\n$proof",
            )
            assertTrue(
                !proof.contains("mse append fake-rest"),
                "fake-rest still skips appendBuffer",
            )
            assertTrue(frames.get() == 0, "video was rendered/decoded frames=${frames.get()}")
            val inited = proof.lineSequence().any {
                it.contains("mse sabr fake-ump") && it.contains(" init=0")
            }
            println("fake format inited (sel had 144p)=${if (inited) "1" else "0"}")
        } finally {
            watch.cancel()
            session.close()
        }
    }

    @Test
    fun leave_audio_then_second_load_plays_audio() = runBlocking {
        if (!youtubeReachable()) {
            println("skip: offline")
            return@runBlocking
        }
        val surface = MseVideoSurface()
        surface.setBlitSize(468, 263)
        val session = HeadlessSession.boot(surface)
        try {
            session.player.init(session.log)
            session.player.setViewport(468, 263, session.log)
            session.player.load(FIRST_ID, session.log, "zero")
            session.player.play(session.log)
            val ready = System.currentTimeMillis() + 45_000
            var pos = 0L
            while (System.currentTimeMillis() < ready) {
                pos = (session.player.status(session.log)["positionMs"] as? JsonPrimitive)?.longOrNull ?: 0L
                if (pos > 2_000 && surface.samplesPlayed() > 8_000) break
                delay(250)
            }
            assertTrue(pos > 2_000, "first audio-only never played pos=$pos samples=${surface.samplesPlayed()}")
            val samplesAt = surface.samplesPlayed()
            val framesAt = surface.presentedFrames()
            val proof0 = session.proofFile.takeIf { it.isFile }?.readText().orEmpty()
            val ms0 = proof0.lineSequence().count { it.contains("mse MediaSource create") }
            println(
                proof0.lineSequence().filter {
                    it.contains("mse fake-fmt") ||
                        it.contains("mse sabr drop") ||
                        it.contains("getAvailableQualityData") ||
                        it.contains("mse isTypeSupported") ||
                        it.contains("mse changeType") ||
                        it.contains("sabr sabr=")
                }.joinToString("\n"),
            )
            assertTrue(proof0.contains("mse fake-fmt 144p itags="), "did not rewrite 144p formats")
            assertNo144pOnSabr(proof0.lineSequence().filter { it.contains("sabr sabr=") }.toList())
            assertTrue(
                proof0.contains("mse isTypeSupported reject codec vp09.02.51.10.01.09.99.99.00"),
                "Chrome VP9 profile probe must reject …99.99.00 so mdp() keeps .fake",
            )
            session.player.preload(SECOND_ID, session.log)
            session.player.load(SECOND_ID, session.log, "zero")
            session.player.play(session.log)
            val deadline = System.currentTimeMillis() + 45_000
            var lastMoveAt = System.currentTimeMillis()
            var lastSamples = samplesAt
            while (System.currentTimeMillis() < deadline) {
                val samples = surface.samplesPlayed()
                pos = (session.player.status(session.log)["positionMs"] as? JsonPrimitive)?.longOrNull ?: 0L
                if (samples > lastSamples + 2_000) lastMoveAt = System.currentTimeMillis()
                lastSamples = samples
                val proof = session.proofFile.takeIf { it.isFile }?.readText().orEmpty()
                val after = proof.substringAfter("vid=$SECOND_ID", "")
                if (samples > samplesAt + 48_000 && after.contains("mse append a=1")) break
                if (System.currentTimeMillis() - lastMoveAt > 8_000 && samples > samplesAt) {
                    break
                }
                delay(400)
            }
            val samples = surface.samplesPlayed()
            val proof = session.proofFile.takeIf { it.isFile }?.readText().orEmpty()
            val after = proof.substringAfter("vid=$SECOND_ID", "")
            val abr = after.lineSequence().filter { it.contains("sabr sabr=") }.toList()
            val append = after.lineSequence().filter { it.contains("mse append a=1") }.toList()
            val addSb = after.lineSequence().filter { it.contains("mse addSourceBuffer") }.toList()
            val ms1 = proof.lineSequence().count { it.contains("mse MediaSource create") }
            println(
                "second-load-zero pos=$pos samples $samplesAt->$samples frames $framesAt->${surface.presentedFrames()} msCreate $ms0->$ms1",
            )
            for (l in addSb) println("  $l")
            for (l in abr.takeLast(6)) println("  $l")
            for (l in append.takeLast(4)) println("  $l")
            assertTrue(append.isNotEmpty(), "second load never appended audio. after=\n$after")
            assertTrue(
                samples > samplesAt + 16_000,
                "DAC stalled after second load-as-zero samples=$samplesAt->$samples",
            )
            assertTrue(
                abr.any { it.contains("40=1") },
                "second load VOD zero SABR missing field 40=1. abr=$abr",
            )
            assertNo144pOnSabr(abr)
            assertTrue(
                surface.presentedFrames() - framesAt <= 2,
                "video decoded after second audio-only load frames=$framesAt->${surface.presentedFrames()}",
            )
            println("mse MediaSource create $ms0->$ms1 (YouTube may open a new MS on loadVideoById)")
        } finally {
            session.close()
        }
    }

    companion object {
        private const val VIDEO_ID = "x72VDoEpTIU"
        private const val INIT_VIDEO_ID = "0AHAkBrBg5I"
        private const val FIRST_ID = "LXb3EKWsInQ"
        private const val SECOND_ID = "nftTbgktw90"
        private val ITAG_144P = setOf(160, 278, 330, 394, 598, 694)

        private fun videoLeftoverCovers(line: String, posMs: Long): Boolean {
            val leftover = Regex(""" leftover=(\d+)""").find(line)
            if (leftover != null && leftover.groupValues[1].toInt() > 0 &&
                line.contains("mse append a=0")
            ) {
                return true
            }
            val m = Regex("""mse=\[a:[^\]]* v:([^\]]*)\]""").find(line) ?: return false
            val raw = m.groupValues[1]
            if (raw.isBlank() || raw == "-") return false
            val tMs = Regex(""" playerTimeMs=(\d+)""").find(line)?.groupValues?.get(1)?.toLong()
                ?: Regex(""" clock=(\d+)""").find(line)?.groupValues?.get(1)?.toLong()
                ?: posMs
            val t = tMs / 1000.0
            for (part in raw.split(',')) {
                val win = Regex("""(\d+)\+(\d+)""").find(part.trim()) ?: continue
                val start = win.groupValues[1].toDouble() / 1000.0
                val dur = win.groupValues[2].toDouble() / 1000.0
                if (t >= start - 0.05 && t <= start + dur + 0.05) return true
            }
            return false
        }

        private fun dumpFakeInitProof(proof: String, pos: Long) {
            val keys = listOf(
                "mse fake-fmt",
                "mse isTypeSupported fake",
                "mse isTypeSupported reject",
                "mse addSourceBuffer",
                "mse changeType",
                "mse append fake-cover",
                "mse sabr fake-ump",
                "FORMAT_INIT",
                "MEDIA_HEADER hid=",
                "sabr sabr=",
                "mse append a=0",
            )
            println("--- fake-init pos=$pos ---")
            for (line in proof.lineSequence()) {
                if (keys.any { line.contains(it) }) println("  $line")
            }
            println("--- /fake-init ---")
        }

        private fun csvItags(raw: String): List<Int> {
            if (raw == "-" || raw.isEmpty()) return emptyList()
            return raw.split(',').mapNotNull { it.toIntOrNull() }
        }

        private fun sabrFieldItags(line: String, key: String): List<Int> {
            val m = Regex(""" $key=([^ ]+)""").find(line) ?: return emptyList()
            return csvItags(m.groupValues[1])
        }

        private fun sabrBrItags(line: String): List<Int> {
            val m = Regex(""" br=\[([^\]]*)\]""").find(line) ?: return emptyList()
            return Regex("""(\d+):""").findAll(m.groupValues[1]).map { it.groupValues[1].toInt() }.toList()
        }

        private fun assertNo144pOnSabr(abr: List<String>) {
            val leaked = mutableListOf<String>()
            for (line in abr) {
                val itags = sabrFieldItags(line, "sel") +
                    sabrFieldItags(line, "vid") +
                    sabrBrItags(line)
                for (t in itags) {
                    if (t in ITAG_144P) leaked.add("$t in $line")
                }
            }
            assertTrue(leaked.isEmpty(), "144p itag sent to SABR:\n${leaked.joinToString("\n")}")
        }
    }
}
