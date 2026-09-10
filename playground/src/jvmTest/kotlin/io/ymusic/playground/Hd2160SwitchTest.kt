package io.ymusic.playground

import io.ymusic.mse.MseVideoSurface
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.test.Test
import kotlin.test.assertTrue

class Hd2160SwitchTest {
    @Test
    fun hd2160_keeps_audio_and_does_not_stick_at_1fps() = runBlocking {
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
            session.player.load(VIDEO_ID, session.log, "auto")
            session.player.play(session.log)
            val ready = System.currentTimeMillis() + 45_000
            var pos = 0L
            while (System.currentTimeMillis() < ready) {
                pos = (session.player.status(session.log)["positionMs"] as? JsonPrimitive)?.longOrNull ?: 0L
                if (pos > 3_000 && surface.presentedFrames() >= 30) break
                delay(250)
            }
            assertTrue(pos > 3_000, "auto never played pos=$pos frames=${surface.presentedFrames()}")
            val framesAt = surface.presentedFrames()
            val samplesAt = surface.samplesPlayed()
            session.player.setQuality("hd2160", session.log)
            delay(500)

            data class Tick(val t: Long, val frames: Int, val samples: Long, val pos: Long)
            val ticks = ArrayList<Tick>()
            val t0 = System.currentTimeMillis()
            while (System.currentTimeMillis() - t0 < 52_000) {
                val p = (session.player.status(session.log)["positionMs"] as? JsonPrimitive)?.longOrNull ?: 0L
                ticks.add(Tick(System.currentTimeMillis() - t0, surface.presentedFrames(), surface.samplesPlayed(), p))
                delay(250)
            }

            println("hd2160 switch pos=$pos frames=$framesAt->$ ${ticks.last().frames} samples=$samplesAt->${ticks.last().samples}")
            var lastF = framesAt
            var lastS = samplesAt
            var lastT = 0L
            var audioStallMs = 0L
            var maxAudioStall = 0L
            val fpsBySec = LinkedHashMap<Int, Int>()
            for (tk in ticks) {
                val dt = (tk.t - lastT).coerceAtLeast(1)
                val df = tk.frames - lastF
                val ds = tk.samples - lastS
                val sec = (tk.t / 1000).toInt()
                fpsBySec[sec] = (fpsBySec[sec] ?: 0) + df
                if (ds <= 0) {
                    audioStallMs += dt
                    if (audioStallMs > maxAudioStall) maxAudioStall = audioStallMs
                } else {
                    audioStallMs = 0
                }
                lastF = tk.frames
                lastS = tk.samples
                lastT = tk.t
            }
            println("hd2160 fps/sec after switch: " + fpsBySec.entries.joinToString { "${it.key}s=${it.value}" })
            println("hd2160 max audio stall ${maxAudioStall}ms samples $samplesAt->${ticks.last().samples}")

            val settle = fpsBySec.filterKeys { it >= 8 }
            val lowSecs = settle.filter { it.value <= 1 }.keys.sorted()
            var streak = 0
            var maxLowStreak = 0
            var prev = -99
            for (s in lowSecs) {
                streak = if (s == prev + 1) streak + 1 else 1
                if (streak > maxLowStreak) maxLowStreak = streak
                prev = s
            }
            assertTrue(
                ticks.last().samples > samplesAt + 8_000,
                "audio not running after hd2160 samples=$samplesAt->${ticks.last().samples}",
            )
            assertTrue(
                maxAudioStall < 1_600,
                "audio stalled ${maxAudioStall}ms after hd2160 (glitch with 0-1fps)",
            )
            assertTrue(
                maxLowStreak < 2,
                "stuck 0-1fps for ${maxLowStreak}s after settle fps=$settle",
            )
        } finally {
            session.close()
        }
    }

    companion object {
        private const val VIDEO_ID = "LXb3EKWsInQ"
    }
}
