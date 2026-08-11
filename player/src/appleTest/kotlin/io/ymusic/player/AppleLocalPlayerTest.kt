package io.ymusic.player

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.Foundation.NSDate
import platform.Foundation.NSDefaultRunLoopMode
import platform.Foundation.NSRunLoop
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.dateByAddingTimeInterval
import platform.Foundation.runMode
import platform.Foundation.timeIntervalSinceDate
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end AVPlayer backend test — runs on macOS (the host) against a generated PCM WAV. Uses
 * [runBlocking] (real time, unlike `runTest`'s virtual clock) because it exercises actual playback:
 * the play-head must advance in wall-clock time.
 */
class AppleLocalPlayerTest {
    private var player: LocalPlayer? = null

    @AfterTest
    fun tearDown() {
        player?.release()
    }

    @Test
    fun loads_plays_pauses_and_seeks() = pumpingMainLoop {
        if (!canPlayRealMedia) return@pumpingMainLoop
        val path = NSTemporaryDirectory() + "localplayer_test_3s.wav"
        writeFile(path, sineWavPcm16(seconds = 3.0))
        val p = createLocalPlayer(LocalPlayerContext()).also { player = it }

        p.load(PlayableItem.LocalMedia(path), playWhenReady = true)
        awaitState(p) { it is PlaybackState.Prepared && !it.buffering }
        assertTrue(p.status.value.state is PlaybackState.Prepared, "load failed: ${p.status.value.error}")
        val duration = p.progress.value.durationMs
        assertTrue(duration in 2500L..3500L, "duration should be ~3000ms, was $duration")

        // Playing: the head advances in real time.
        delay(1200)
        val playing = p.progress.value.positionMs
        assertTrue(playing > 400L, "position should advance while playing, was $playing")

        // Paused: the head holds.
        p.pause()
        delay(300)
        val atPause = p.progress.value.positionMs
        delay(500)
        val stillPaused = p.progress.value.positionMs
        assertTrue(stillPaused - atPause < 150L, "should not advance while paused ($atPause -> $stillPaused)")
        assertEquals(false, p.status.value.playWhenReady)

        // Seek moves the head.
        p.seekTo(2000)
        delay(300)
        assertTrue(p.progress.value.positionMs >= 1800L, "seek should land near 2000ms, was ${p.progress.value.positionMs}")

        // Resume to the natural end.
        p.play()
        var waited = 0
        while (!p.status.value.state.isEnded() && waited < 4000) {
            delay(200); waited += 200
        }
        assertTrue(p.status.value.state.isEnded(), "should reach the end of the file")
    }

    @Test
    fun bad_uri_fails_typed() = pumpingMainLoop {
        val p = createLocalPlayer(LocalPlayerContext()).also { player = it }
        p.load(PlayableItem.LocalMedia(NSTemporaryDirectory() + "does_not_exist.wav"), playWhenReady = false)
        awaitState(p) { it is PlaybackState.Error }
        assertTrue(p.status.value.state is PlaybackState.Error, "loading a missing file must fail")
        assertTrue(p.status.value.error?.code?.startsWith("E600") == true)
    }

    @Test
    fun owns_surface_visibility_for_active_source() {
        val surface = RecordingApplePlayerSurface()
        val p = AppleLocalPlayer { surface }.also { player = it }

        p.load(PlayableItem.LocalMedia(NSTemporaryDirectory() + "surface-test.wav"), playWhenReady = false)
        assertEquals(listOf(true), surface.visibility)

        p.stop()
        assertEquals(listOf(true, false), surface.visibility)

        p.release()
        assertEquals(listOf(true, false, false), surface.visibility)
        assertTrue(surface.released)
    }

    /**
     * Real multi-track validation of the capability interfaces on AVFoundation — a generated MP4 with
     * a video track, two audio languages, and two subtitle languages. Skipped when ffmpeg is absent.
     */
    @Test
    fun multitrack_capabilities() = pumpingMainLoop {
        val path = NSTemporaryDirectory() + "localplayer_multitrack.mp4"
        if (!generateMultiTrack(path)) return@pumpingMainLoop // ffmpeg not installed → skip
        val p = createLocalPlayer(LocalPlayerContext()).also { player = it }

        p.load(PlayableItem.LocalMedia(path), playWhenReady = false)
        awaitState(p) { it is PlaybackState.Prepared && !it.buffering }
        assertTrue(p.status.value.state is PlaybackState.Prepared)

        val audio = p.audioTracks.value
        assertTrue(audio.tracks.size >= 2, "expected ≥2 audio tracks, got ${audio.tracks}")
        val captions = p.captions.value
        assertTrue(captions.tracks.isNotEmpty(), "expected caption tracks, got ${captions.tracks}")
        assertTrue(p.videoQuality.value.options.isNotEmpty(), "expected video-quality options")

        // Selecting the second audio track updates the state.
        p.selectAudioTrack(audio.tracks[1].id)
        delay(200)
        assertEquals(audio.tracks[1].id, p.audioTracks.value.selectedId, "audio selection should stick")

        // Enabling a caption track (via config) updates the state.
        p.setConfig(PlaybackConfig(preferCaptionLanguage = captions.tracks.first().languageCode to 1L, captionVisible = true))
        delay(200)
        assertEquals(
            captions.tracks.first().languageCode, p.captions.value.selectedLanguage,
            "caption selection should stick",
        )
    }
}

private class RecordingApplePlayerSurface : ApplePlayerSurface {
    val visibility = mutableListOf<Boolean>()
    var released = false

    override fun setVisible(visible: Boolean) {
        visibility += visible
    }

    override fun release() {
        released = true
    }
}

/** Generate a video + 2-audio + 2-subtitle MP4 via ffmpeg; false if ffmpeg is not installed. */
private fun generateMultiTrack(path: String): Boolean {
    if (runShell("command -v ffmpeg >/dev/null 2>&1") != 0) return false
    val en = NSTemporaryDirectory() + "cap_en.srt"
    val es = NSTemporaryDirectory() + "cap_es.srt"
    writeFile(en, "1\n00:00:00,000 --> 00:00:02,000\nHello\n".encodeToByteArray())
    writeFile(es, "1\n00:00:00,000 --> 00:00:02,000\nHola\n".encodeToByteArray())
    val cmd = "ffmpeg -y -hide_banner -loglevel error " +
        "-f lavfi -i testsrc=duration=2:size=320x240:rate=15 " +
        "-f lavfi -i sine=frequency=440:duration=2 " +
        "-f lavfi -i sine=frequency=880:duration=2 " +
        "-i $en -i $es " +
        "-map 0:v -map 1:a -map 2:a -map 3:s -map 4:s " +
        "-c:v libx264 -c:a aac -c:s mov_text " +
        "-metadata:s:a:0 language=eng -metadata:s:a:1 language=spa " +
        "-metadata:s:s:0 language=eng -metadata:s:s:1 language=spa " +
        path
    return runShell(cmd) == 0
}

/** Poll [player]'s status until it reaches [target] (or a competing terminal state), with a timeout. */
private suspend fun awaitState(
    player: LocalPlayer,
    timeoutMs: Int = 16_000,
    predicate: (PlaybackState) -> Boolean,
) {
    var waited = 0
    while (!predicate(player.status.value.state) && player.status.value.state !is PlaybackState.Error && waited < timeoutMs) {
        delay(50)
        waited += 50
    }
}

/**
 * Runs [block] on a background dispatcher while the calling (test-main) thread drives the main run
 * loop. AVFoundation advances `AVPlayerItem` readiness on the main dispatch queue; a plain
 * `runBlocking` on the test's main thread would starve it and every load would time out. In a real
 * app the host's main run loop provides this pumping.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private fun pumpingMainLoop(timeoutMs: Long = 30_000, block: suspend () -> Unit) {
    val done = CompletableDeferred<Unit>()
    CoroutineScope(Dispatchers.Default).launch {
        try {
            block()
            done.complete(Unit)
        } catch (t: Throwable) {
            done.completeExceptionally(t)
        }
    }
    val start = NSDate()
    while (!done.isCompleted) {
        NSRunLoop.mainRunLoop().runMode(NSDefaultRunLoopMode, beforeDate = NSDate().dateByAddingTimeInterval(0.02))
        if (NSDate().timeIntervalSinceDate(start) > timeoutMs / 1000.0) error("test timed out")
    }
    done.getCompleted() // rethrows a failure from the background block
}

/** Minimal 16-bit mono PCM WAV holding a sine tone — a valid container AVPlayer decodes. */
private fun sineWavPcm16(seconds: Double, sampleRate: Int = 44100, freqHz: Double = 440.0): ByteArray {
    val numSamples = (seconds * sampleRate).toInt()
    val dataSize = numSamples * 2
    val bytes = ByteArray(44 + dataSize)
    var p = 0
    fun str(s: String) { for (c in s) bytes[p++] = c.code.toByte() }
    fun u32(v: Int) {
        bytes[p++] = (v and 0xff).toByte(); bytes[p++] = ((v shr 8) and 0xff).toByte()
        bytes[p++] = ((v shr 16) and 0xff).toByte(); bytes[p++] = ((v shr 24) and 0xff).toByte()
    }
    fun u16(v: Int) { bytes[p++] = (v and 0xff).toByte(); bytes[p++] = ((v shr 8) and 0xff).toByte() }

    str("RIFF"); u32(36 + dataSize); str("WAVE")
    str("fmt "); u32(16); u16(1); u16(1); u32(sampleRate); u32(sampleRate * 2); u16(2); u16(16)
    str("data"); u32(dataSize)
    for (i in 0 until numSamples) {
        val sample = (sin(2 * PI * freqHz * i / sampleRate) * 0.3 * Short.MAX_VALUE).toInt()
        bytes[p++] = (sample and 0xff).toByte(); bytes[p++] = ((sample shr 8) and 0xff).toByte()
    }
    return bytes
}

@OptIn(ExperimentalForeignApi::class)
private fun writeFile(path: String, data: ByteArray) {
    val f = fopen(path, "wb") ?: error("cannot open $path for writing")
    try {
        data.usePinned { pinned ->
            fwrite(pinned.addressOf(0), 1.convert(), data.size.convert(), f)
        }
    } finally {
        fclose(f)
    }
}
