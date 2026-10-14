package io.ymusic.playground

import io.ymusic.mse.MseHostNative
import io.ymusic.mse.MseVideoSurface
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import projectcommon.json.longOrNull
import java.io.File
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
                dur = st.longOrNull("durationMs") ?: 0L
                if (frames.get() >= 2 && dur > 0) break
                delay(250)
            }
            println(
                "frames=${frames.get()} dur=$dur size=${surface.frame.value?.let { "${it.width}x${it.height}" }}",
            )
            assertTrue(frames.get() >= 2, "MSE never published video frames frames=${frames.get()}")

            session.player.play(session.log)
            delay(400)
            val moving = session.player.status(session.log).longOrNull("positionMs") ?: 0L
            delay(1_200)
            val later = session.player.status(session.log).longOrNull("positionMs") ?: 0L
            println("clock $moving -> $later")
            assertTrue(later > moving, "play did not advance clock $moving -> $later")

            session.player.pause(session.log)
            delay(400)
            val pausedAt = session.player.status(session.log).longOrNull("positionMs") ?: 0L
            delay(800)
            val still = session.player.status(session.log).longOrNull("positionMs") ?: 0L
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
                landed = session.player.status(session.log).longOrNull("positionMs") ?: 0L
                if (landed in 145_000..165_000) break
                delay(250)
            }
            println("hole-seek land -> $landed frames=${frames.get()}")
            assertTrue(landed in 145_000..165_000, "seek did not land near 150s landed=$landed")
            val playDeadline = System.currentTimeMillis() + 15_000
            var afterSeek = landed
            while (System.currentTimeMillis() < playDeadline) {
                afterSeek = session.player.status(session.log).longOrNull("positionMs") ?: 0L
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
                it.contains("bg generateIT") || it.contains("minter GenerateIT") || it.contains("owl m")
            }
            val spsLines = proof.lineSequence().filter { it.contains("SPS=") }.toList()
            val sps3 = spsLines.any { it.contains("SPS=3") }
            val sps1 = spsLines.any { it.contains("SPS=1") }
            println("po-proof minter=$minter sps1=$sps1 sps3=$sps3 spsLines=${spsLines.size}")
            for (line in spsLines.takeLast(12)) println("sps $line")
            assertTrue(minter != null, "player minter did not run")
            assertTrue(sps1, "no SABR SPS=1 (ok) after hole-seek spsLines=$spsLines")
            assertTrue(!sps3, "SABR SPS=3 po-invalid minter=$minter")
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
        val session = HeadlessSession.boot(surface)
        try {
            session.player.load(VIDEO_ID, session.log, "auto")
            session.player.setViewport(1920, 1080, session.log)
            session.player.play(session.log)
            session.watchPlay(
                surface,
                minDelta = 2_000,
                timeoutMs = 60_000,
                audioStallBudget = STARTUP_AUDIO_STALL_MS,
                label = "first play",
                requireNoJump = false,
            )

            session.switchQuality(surface, "hd1080", forced = true, label = "hd1080")
            session.switchQuality(surface, "tiny", forced = true, label = "tiny")
            session.switchQuality(surface, "auto", forced = false, label = "auto")

            session.player.seekTo(30_000, session.log)
            session.player.play(session.log)
            val land = session.awaitLand(25_000L..40_000L, timeoutMs = 20_000, label = "seek 30s")
            session.watchPlay(
                surface,
                from = land,
                minDelta = 800,
                timeoutMs = 15_000,
                audioStallBudget = STARTUP_AUDIO_STALL_MS,
                label = "play after seek",
                requireNoJump = false,
            )
            session.switchQuality(surface, "hd1080", forced = true, label = "hd1080 after seek")

            /* log21: seek far past leftover — clock ran, fps=0, audio stopped. */
            session.player.seekTo(150_000, session.log)
            session.player.play(session.log)
            val far = session.awaitLand(140_000L..165_000L, timeoutMs = 20_000, label = "seek 150s")
            session.watchPlay(
                surface,
                from = far,
                minDelta = 2_000,
                timeoutMs = 20_000,
                audioStallBudget = STARTUP_AUDIO_STALL_MS,
                label = "play after far seek",
                requireNoJump = false,
            )
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
        val surface = MseVideoSurface()
        val session = HeadlessSession.boot(surface)
        try {
            session.player.load(VIDEO_ID, session.log, "auto")
            session.player.setViewport(1920, 1080, session.log)
            session.player.play(session.log)
            session.watchPlay(
                surface,
                minDelta = 2_000,
                timeoutMs = 60_000,
                audioStallBudget = STARTUP_AUDIO_STALL_MS,
                label = "first video",
                requireNoJump = false,
            )
            session.switchQuality(surface, "hd1080", forced = true, label = "hd1080")
            session.switchQuality(surface, "tiny", forced = true, label = "tiny")

            val proof = session.proofFile.takeIf { it.isFile }?.readText().orEmpty()
            val srcs = proof.lineSequence().filter { it.contains("mse video.src blob:mse/") }.toList()
            println("quality srcs=${srcs.size}")
            for (s in srcs) println("  src $s")

            session.player.load(SECOND_ID, session.log, "auto")
            session.player.play(session.log)
            val land = session.awaitLand(0L..20_000L, timeoutMs = 60_000, label = "second load")
            session.watchPlay(
                surface,
                from = land,
                minDelta = 800,
                timeoutMs = 20_000,
                audioStallBudget = STARTUP_AUDIO_STALL_MS,
                label = "second load play",
                requireNoJump = false,
            )
            dumpMseProof(session, "quality_then_second_load")
        } finally {
            session.close()
        }
    }

    /**
     * log21: auto at 640×360 (itag 397), grow the viewport so ABR appends 398,
     * then seek. 398 demuxed ×5 (18s → 90s, 266s → 1321s) so the clock ran
     * with fps=0. Catch that: presented frames + PCM after the seek.
     */
    @Test
    fun auto_viewport_grow_then_far_seek_plays() = runBlocking {
        if (!youtubeReachable()) {
            println("skip: offline")
            return@runBlocking
        }
        val surface = MseVideoSurface()
        val session = HeadlessSession.boot(surface)
        try {
            session.player.load(LONG_ID, session.log, "auto", 256, 144)
            session.player.setViewport(256, 144, session.log)
            session.player.play(session.log)
            session.watchPlay(
                surface,
                minDelta = 2_000,
                timeoutMs = 60_000,
                audioStallBudget = STARTUP_AUDIO_STALL_MS,
                label = "auto 144p",
                requireNoJump = false,
            )
            run {
                val proof = session.proofFile.takeIf { it.isFile }?.readText().orEmpty()
                val low = listOf("394:", "395:", "396:", "397:").any { proof.contains(it) }
                println("small viewport low-itag=$low")
                assertTrue(low, "small viewport did not start on a low itag (394-397)")
            }

            session.player.setViewport(1920, 1080, session.log)
            session.player.play(session.log)
            val up = System.currentTimeMillis() + 20_000
            var saw398 = false
            while (System.currentTimeMillis() < up) {
                val proof = session.proofFile.takeIf { it.isFile }?.readText().orEmpty()
                if (proof.contains("398:") || proof.contains("399:")) {
                    saw398 = true
                    break
                }
                delay(250)
            }
            println("viewport grow higher-itag=$saw398")
            assertTrue(saw398, "ABR did not append 398/399 after viewport 1920x1080")

            session.player.seekTo(266_000, session.log)
            session.player.play(session.log)
            val land = session.awaitLand(250_000L..280_000L, timeoutMs = 25_000, label = "seek 266s")
            session.watchPlay(
                surface,
                from = land,
                minDelta = 2_000,
                timeoutMs = 20_000,
                audioStallBudget = STARTUP_AUDIO_STALL_MS,
                label = "play after 266s",
                requireNoJump = false,
            )
            val proof = session.proofFile.takeIf { it.isFile }?.readText().orEmpty()
            val leftoverStuck = proof.lineSequence().any { it.contains("1321474+") }
            assertTrue(!leftoverStuck, "398 leftover still at ×5 timestamps (1321s) after 266s seek")
            dumpMseProof(session, "auto_viewport_grow_then_far_seek")
        } finally {
            session.close()
        }
    }

    /**
     * log21: only 480p (itag 397, 854×480) has a broken right edge. 854 % 8 = 6,
     * so libswscale bilinear SIMD drops the last 6 columns (black or white strip).
     * 144p/720p/1080p are 8-aligned; 240p remainder 2 is fine.
     */
    @Test
    fun av1_480p_right_edge_not_garbage() = runBlocking {
        if (!youtubeReachable()) {
            println("skip: offline")
            return@runBlocking
        }
        val surface = MseVideoSurface()
        val session = HeadlessSession.boot(surface)
        try {
            session.player.load(LONG_ID, session.log, "large", 854, 480)
            session.player.setViewport(854, 480, session.log)
            session.player.setQuality("large", false, session.log)
            session.player.play(session.log)
            val deadline = System.currentTimeMillis() + 60_000
            var saw854 = 0
            var last = "none"
            var gen = -1
            while (System.currentTimeMillis() < deadline && saw854 < 3) {
                val frame = MseHostNative.copyFrame(surface.host, gen)
                if (frame == null) {
                    delay(20)
                    continue
                }
                gen = frame.gen
                last = "${frame.width}x${frame.height}"
                if (frame.width != 854 || frame.height != 480) continue
                val garbage = rightEdgeFlatGarbageBgra(frame.bgra, frame.width, frame.height)
                println("480p frame gen=$gen ${frame.width}x${frame.height} right-edge-garbage=$garbage")
                if (garbage) {
                    dumpMseProof(session, "480p right edge")
                    throw AssertionError("854x480 rightmost columns are flat black/white (sws SIMD tail)")
                }
                saw854++
            }
            assertTrue(saw854 >= 3, "never got 854x480 frames (last=$last)")
            dumpMseProof(session, "480p right edge ok")
        } finally {
            session.close()
        }
    }

    /**
     * log21: clicking 480→360→240→144 in a few seconds reopens dav1d.
     * Mid-GOP P-frames return INVALIDDATA, then later keys fail too
     * (decoder poisoned). Clock/audio keep running, video frozen.
     */
    @Test
    fun fast_quality_switch_video_keeps_presenting() = runBlocking {
        if (!youtubeReachable()) {
            println("skip: offline")
            return@runBlocking
        }
        val surface = MseVideoSurface()
        val session = HeadlessSession.boot(surface)
        try {
            session.player.load(LONG_ID, session.log, "auto")
            session.player.setViewport(1280, 720, session.log)
            session.player.play(session.log)
            session.watchPlay(
                surface,
                minDelta = 2_000,
                timeoutMs = 60_000,
                audioStallBudget = STARTUP_AUDIO_STALL_MS,
                label = "before switch",
                requireNoJump = false,
            )
            for (q in listOf("large", "medium", "small", "tiny")) {
                session.player.setQuality(q, false, session.log)
                delay(250)
            }
            session.player.play(session.log)
            /* Four MediaSource recreations; SABR refill is a startup, not a
               single forced-quality 0.5s stall. Video must present again. */
            session.watchPlay(
                surface,
                minDelta = 2_000,
                timeoutMs = 30_000,
                audioStallBudget = STARTUP_AUDIO_STALL_MS,
                label = "after fast quality",
                requireNoJump = false,
            )
            dumpMseProof(session, "fast_quality_switch")
        } finally {
            session.close()
        }
    }

    /**
     * log23: live 144p leftover covered currentTime; 1080p init dropped
     * vf and left a hole. Chrome keeps leftover and switches at the new
     * GOP. Fail if fps stays 0 or audio stops while the clock runs.
     */
    @Test
    fun live_quality_switch_plays_leftover() = runBlocking {
        if (!youtubeReachable()) {
            println("skip: offline")
            return@runBlocking
        }
        val surface = MseVideoSurface()
        val session = HeadlessSession.boot(surface)
        try {
            session.player.load(LIVE_ID, session.log, "auto")
            session.player.setViewport(1920, 1080, session.log)
            session.player.play(session.log)
            session.watchPlay(
                surface,
                minDelta = 2_000,
                timeoutMs = 60_000,
                audioStallBudget = STARTUP_AUDIO_STALL_MS,
                label = "live before switch",
                requireNoJump = false,
            )
            session.player.setQuality("tiny", false, session.log)
            delay(1_500)
            session.player.setQuality("hd1080", false, session.log)
            session.player.play(session.log)
            session.watchPlay(
                surface,
                minDelta = 6_500,
                timeoutMs = 30_000,
                audioStallBudget = FORCED_AUDIO_STALL_MS,
                label = "live 144p->1080p",
                requireNoJump = true,
            )
            dumpMseProof(session, "live_quality_switch")
        } finally {
            session.close()
        }
    }

    /**
     * log21: after a far seek, 720p GOPs reopen dav1d at the same 1280×720.
     * Mid-GOP P-frames fail, recover skipped to the next key (~3s), audio
     * starve-ahead, picture frozen. Play through several GOPs after 944s.
     */
    @Test
    fun hd720_after_far_seek_no_gop_stall() = runBlocking {
        if (!youtubeReachable()) {
            println("skip: offline")
            return@runBlocking
        }
        val surface = MseVideoSurface()
        val session = HeadlessSession.boot(surface)
        try {
            session.player.load(LONG_ID, session.log, "hd720", 1280, 720)
            session.player.setViewport(1280, 720, session.log)
            session.player.play(session.log)
            session.watchPlay(
                surface,
                minDelta = 2_000,
                timeoutMs = 60_000,
                audioStallBudget = STARTUP_AUDIO_STALL_MS,
                label = "hd720 start",
                requireNoJump = false,
            )
            session.player.seekTo(944_000, session.log)
            session.player.play(session.log)
            val land = session.awaitLand(930_000L..960_000L, timeoutMs = 25_000, label = "seek 944s")
            session.watchPlay(
                surface,
                from = land,
                minDelta = 8_000,
                timeoutMs = 30_000,
                audioStallBudget = STARTUP_AUDIO_STALL_MS,
                label = "hd720 after 944s",
                requireNoJump = false,
                videoLagBudget = 2_000L,
            )
            dumpMseProof(session, "hd720_after_far_seek")
        } finally {
            session.close()
        }
    }

    /**
     * Capture auto↔144p (tiny) on VOD: FORMAT_INIT, MEDIA_HEADER,
     * changeType, appendBuffer. Write the proof log for the write-up.
     */
    @Test
    fun gapless_vod_auto_tiny_auto() = runBlocking {
        if (!youtubeReachable()) {
            println("skip: offline")
            return@runBlocking
        }
        val surface = MseVideoSurface()
        val session = HeadlessSession.boot(surface)
        try {
            session.player.load(VIDEO_ID, session.log, "auto")
            session.player.setViewport(1920, 1080, session.log)
            session.player.play(session.log)
            session.watchPlay(
                surface,
                minDelta = 3_000,
                timeoutMs = 60_000,
                audioStallBudget = STARTUP_AUDIO_STALL_MS,
                label = "vod auto",
                requireNoJump = false,
            )
            session.switchQuality(surface, "tiny", forced = true, label = "vod tiny")
            session.switchQuality(surface, "auto", forced = false, label = "vod auto-back")
            dumpGaplessProof(session, "vod")
        } finally {
            session.close()
        }
    }

    /**
     * Same auto↔144p capture on live. Tiny pin is a real quality change
     * here (not audio-only). FORMAT_INIT vs media MH is the point.
     */
    @Test
    fun gapless_live_auto_tiny_auto() = runBlocking {
        if (!youtubeReachable()) {
            println("skip: offline")
            return@runBlocking
        }
        val surface = MseVideoSurface()
        val session = HeadlessSession.boot(surface)
        try {
            session.player.load(LIVE_ID, session.log, "auto")
            session.player.setViewport(1920, 1080, session.log)
            session.player.play(session.log)
            session.watchPlay(
                surface,
                minDelta = 3_000,
                timeoutMs = 60_000,
                audioStallBudget = STARTUP_AUDIO_STALL_MS,
                label = "live auto",
                requireNoJump = false,
            )
            session.switchQuality(surface, "tiny", forced = true, label = "live tiny")
            session.switchQuality(surface, "auto", forced = false, label = "live auto-back")
            dumpGaplessProof(session, "live")
        } finally {
            session.close()
        }
    }

    companion object {
        private const val VIDEO_ID = "x72VDoEpTIU"
        private const val SECOND_ID = "wjhyk-Cx6wk"
        private const val LONG_ID = "wjhyk-Cx6wk"
        private const val LIVE_ID = "0_LTL7O1xX0"
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
    player.status(log).longOrNull("positionMs") ?: 0L

private suspend fun HeadlessSession.switchQuality(
    surface: MseVideoSurface,
    quality: String,
    forced: Boolean,
    label: String,
) {
    val before = clockMs()
    player.setQuality(quality, false, log)
    player.play(log)
    watchPlay(
        surface,
        from = before,
        minDelta = 6_500,
        timeoutMs = 30_000,
        audioStallBudget = if (forced) FORCED_AUDIO_STALL_MS else AUTO_AUDIO_STALL_MS,
        label = label,
        requireNoJump = true,
    )
}

/**
 * Clock moving is not playback. Fail if native fps stays 0 or DAC/pull
 * samples do not advance (log21: starve-ahead currentTime with no A/V).
 *
 * YouTube: a quality change may snap to a nearby RAP; forced quality may
 * stall A+V ~0.5s; auto plays leftover then switches. Video may lag 5s
 * behind running audio. A jump farther than [NEARBY_SNAP_MS] is a restart.
 */
private suspend fun HeadlessSession.watchPlay(
    surface: MseVideoSurface,
    from: Long? = null,
    minDelta: Long,
    timeoutMs: Long,
    audioStallBudget: Long,
    label: String,
    requireNoJump: Boolean,
    videoLagBudget: Long = VIDEO_LAG_MS,
): Pair<Long, Long> {
    var start = from ?: clockMs()
    val deadline = System.currentTimeMillis() + timeoutMs
    var lastClock = start
    var lastWall = System.currentTimeMillis()
    var baseFrames = surface.presentedFrames()
    var baseSamples = surface.samplesPlayed()
    var lastFrames = baseFrames
    var lastSamples = baseSamples
    var audioGap = 0L
    var videoGap = 0L
    var maxAudioStall = 0L
    var maxVideoLag = 0L
    var maxJump = 0L
    while (System.currentTimeMillis() < deadline) {
        delay(100)
        val wall = System.currentTimeMillis()
        val now = clockMs()
        val wallDt = wall - lastWall
        val frames = surface.presentedFrames()
        val samples = surface.samplesPlayed()
        if (now + JUMP_MS < lastClock) {
            val jump = lastClock - now
            maxJump = maxOf(maxJump, jump)
            if (jump <= NEARBY_SNAP_MS) {
                println("$label nearby snap $lastClock -> $now")
                start = now
                lastClock = now
                lastWall = wall
                baseFrames = frames
                baseSamples = samples
                lastFrames = frames
                lastSamples = samples
                audioGap = 0
                videoGap = 0
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
        if (samples > lastSamples) {
            audioGap = 0
        } else if (clockDt >= 50) {
            audioGap += wallDt
            maxAudioStall = maxOf(maxAudioStall, audioGap)
        }
        if (frames > lastFrames) {
            videoGap = 0
        } else if (samples > lastSamples) {
            videoGap += wallDt
            maxVideoLag = maxOf(maxVideoLag, videoGap)
        }
        lastClock = now
        lastWall = wall
        lastFrames = frames
        lastSamples = samples
        if (maxAudioStall > audioStallBudget) {
            dumpMseProof(this, "$label AUDIO-STALL")
            throw AssertionError(
                "$label audio not running ${maxAudioStall}ms (budget ${audioStallBudget}ms) " +
                    "clock=$now samples=$samples Δ=${samples - baseSamples}",
            )
        }
        if (maxVideoLag > videoLagBudget) {
            dumpMseProof(this, "$label VIDEO-LAG")
            throw AssertionError(
                "$label fps=0 for ${maxVideoLag}ms while audio ran (budget ${videoLagBudget}ms) " +
                    "clock=$now frames=$frames Δ=${frames - baseFrames}",
            )
        }
        if (now > start + minDelta) {
            val dFrames = frames - baseFrames
            val dSamples = samples - baseSamples
            val elapsed = (now - start).coerceAtLeast(1L)
            val fps = dFrames * 1000.0 / elapsed
            println(
                "$label clock $start -> $now audioStall=${maxAudioStall}ms videoLag=${maxVideoLag}ms " +
                    "jump=${maxJump}ms frames=$dFrames fps=${"%.1f".format(fps)} samples=$dSamples",
            )
            if (dFrames <= 0) {
                dumpMseProof(this, "$label FPS=0")
                throw AssertionError("$label fps=0 clock moved $start -> $now frames=$frames")
            }
            if (dSamples <= 0) {
                dumpMseProof(this, "$label NO-AUDIO")
                throw AssertionError("$label audio not run clock moved $start -> $now samples=$samples")
            }
            return start to now
        }
    }
    dumpMseProof(this, "$label TIMEOUT")
    throw AssertionError(
        "$label did not advance $start -> $lastClock (need +$minDelta) " +
            "audioStall=${maxAudioStall}ms videoLag=${maxVideoLag}ms " +
            "frames=${surface.presentedFrames() - baseFrames} samples=${surface.samplesPlayed() - baseSamples}",
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

/** True when the last (width % 8) columns are ~all black or ~all white. */
private fun rightEdgeFlatGarbageBgra(bgra: ByteArray, w: Int, h: Int): Boolean {
    val tail = w and 7
    if (tail == 0 || bgra.size < w * h * 4) return false
    val n = h * tail
    var black = 0
    var white = 0
    for (y in 0 until h) {
        for (x in (w - tail) until w) {
            val i = (y * w + x) * 4
            val b = bgra[i].toInt() and 0xFF
            val g = bgra[i + 1].toInt() and 0xFF
            val r = bgra[i + 2].toInt() and 0xFF
            if (r == 0 && g == 0 && b == 0) black++
            else if (r == 255 && g == 255 && b == 255) white++
        }
    }
    return black * 10 >= n * 9 || white * 10 >= n * 9
}

private fun dumpGaplessProof(session: HeadlessSession, label: String) {
    val proof = session.proofFile.takeIf { it.isFile }?.readText().orEmpty()
    val keys = listOf(
        "mse setQuality",
        "mse changeType",
        "FORMAT_INIT",
        "MEDIA_HEADER",
        "mse append",
        "sabr HTTP",
        "video-rep",
        "changeType",
        "mse drain setQuality",
    )
    println("--- gapless $label ---")
    val lines = proof.lineSequence().filter { line -> keys.any { line.contains(it) } }.toList()
    for (line in lines) println("  $line")
    println("--- /gapless $label n=${lines.size} ---")
    val dest = File(File(System.getProperty("user.dir"), "build"), "gapless-$label.log")
    dest.parentFile.mkdirs()
    dest.writeText(proof)
    println("wrote ${dest.absolutePath} bytes=${dest.length()}")
}

private fun dumpMseProof(session: HeadlessSession, label: String) {
    val proof = session.proofFile.takeIf { it.isFile }?.readText().orEmpty()
    val keys = listOf(
        "mse video.src",
        "mse leftover",
        "mse append",
        "changeType",
        "mse setQuality",
        "FORMAT_INIT",
        "MEDIA_HEADER",
        "sabr HTTP",
        "video-rep",
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
