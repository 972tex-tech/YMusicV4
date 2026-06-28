package kmpffmpeg

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.buffer
import okio.use
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Shared smoke-test body, invoked by the JVM and desktop-native `@Test` wrappers. Lives in commonTest
 * only so it compiles for every target; it is never wired to a `@Test` on iOS (where `startProcess`
 * throws). Skips itself when this host ships no runnable ffmpeg binary (see [FfmpegTestConfig]).
 */
internal suspend fun ffmpegSmokeChecks() {
    val bin = FfmpegTestConfig.binaryPath ?: return // no host-runnable binary → skip

    // 1) `-version`: prints "ffmpeg version …" to stdout and exits 0.
    val version = KmpProcessBuilder(bin).appendArg("-version").start()
    val versionOut = withContext(Dispatchers.Default) { version.stdout.buffer().readUtf8() }
    assertEquals(0, version.exitCode.await(), "ffmpeg -version should exit 0")
    assertTrue(version.pid > 0, "expected a real pid")
    assertTrue(
        versionOut.startsWith("ffmpeg version"),
        "unexpected -version stdout: ${versionOut.take(80)}"
    )

    // 2) `-i <non-media file>`: ffmpeg writes its diagnostic to stderr (the binary is a present non-media file).
    val probe = KmpProcessBuilder(bin).appendArg("-i", "file:$bin").start()
    val probeErr = withContext(Dispatchers.Default) { probe.stderr.buffer().readUtf8() }
    probe.exitCode.await()
    assertTrue(probeErr.isNotBlank(), "expected a diagnostic on stderr, got nothing")
    assertTrue(
        probeErr.contains("Invalid data") || probeErr.contains("Error opening"),
        "unexpected -i stderr: ${probeErr.takeLast(200)}"
    )
}

/**
 * Real media smoke test: run `ffmpeg -i` over the committed fixtures under `kmpFfmpeg/testdata` and
 * assert the embedded metadata + duration come back on stderr.
 */
internal suspend fun ffmpegMetadataChecks() {
    val bin = FfmpegTestConfig.binaryPath ?: return

    val fixtures = listOf(
        "sample_with_metadata.m4a",  // ipod/MP4 + AAC
        "sample_with_metadata.flac", // native FLAC
        "sample_with_metadata.mka",  // Matroska + AAC
    )
    for (name in fixtures) {
        val proc = KmpProcessBuilder(bin)
            .appendArg("-i", "file:${FfmpegTestConfig.testDataDir}/$name")
            .start()
        val err = withContext(Dispatchers.Default) { proc.stderr.buffer().readUtf8() }
        proc.exitCode.await()
        assertTrue("Midnight Drive" in err, "title missing for $name:\n$err")
        assertTrue("The Kotlin Quartet" in err, "artist missing for $name:\n$err")
        assertTrue("Multiplatform Sessions" in err, "album missing for $name:\n$err")
        assertTrue("Synthwave" in err, "genre missing for $name:\n$err")
        assertTrue("Duration: 00:00:02" in err, "duration missing for $name:\n$err")
        assertTrue("Audio:" in err, "audio stream not reported for $name:\n$err")
    }
}

/**
 * Anti-hang check for discard: transcode a fixture to WAV on stdout (~170 KB, past the ~64 KB pipe
 * buffer) while discarding both streams. With discard the child writes to /dev/null and completes; if
 * either stream were piped and left undrained, ffmpeg would deadlock. Also asserts a discarded stream
 * reads as EOF.
 */
internal suspend fun ffmpegDiscardCheck() {
    val bin = FfmpegTestConfig.binaryPath ?: return

    val proc = KmpProcessBuilder(bin)
        .appendArg("-i", "file:${FfmpegTestConfig.testDataDir}/sample_with_metadata.m4a")
        .appendArg("-f", "wav", "pipe:1")
        .discardStdout()
        .discardStderr()
        .start()

    val out = withContext(Dispatchers.Default) { proc.stdout.buffer().readUtf8() }
    val err = withContext(Dispatchers.Default) { proc.stderr.buffer().readUtf8() }
    assertEquals(0, proc.exitCode.await(), "transcode with discarded streams should exit 0")
    assertEquals("", out, "discarded stdout must read as EOF/empty")
    assertEquals("", err, "discarded stderr must read as EOF/empty")
}

/**
 * Captures a large piped stdout (a fixture transcoded to WAV, ~170 KB) and verifies the bytes arrive
 * intact — exercising the multi-segment read in the native source.
 */
internal suspend fun ffmpegLargeStdoutCheck() {
    val bin = FfmpegTestConfig.binaryPath ?: return

    val proc = KmpProcessBuilder(bin)
        .appendArg("-i", "file:${FfmpegTestConfig.testDataDir}/sample_with_metadata.m4a")
        .appendArg("-f", "wav", "pipe:1")
        .discardStderr()
        .start()
    val wav = withContext(Dispatchers.Default) { proc.stdout.buffer().readByteArray() }
    assertEquals(0, proc.exitCode.await(), "wav transcode should exit 0")
    assertTrue(wav.size > 100_000, "expected a large WAV, got ${wav.size} bytes")
    assertEquals("RIFF", wav.copyOfRange(0, 4).decodeToString(), "captured stdout is not a WAV")
}

/**
 * Feeds a (large, streamable) WAV into ffmpeg's stdin and decodes it — exercising the native sink
 * (buffer → fd) write path. The caller drives stdin itself; this module only launches the process.
 */
internal suspend fun ffmpegStdinPipeCheck() {
    val bin = FfmpegTestConfig.binaryPath ?: return

    // 1) Produce a streamable WAV in memory.
    val transcode = KmpProcessBuilder(bin)
        .appendArg("-i", "file:${FfmpegTestConfig.testDataDir}/sample_with_metadata.m4a")
        .appendArg("-f", "wav", "pipe:1")
        .discardStderr()
        .start()
    val wav = withContext(Dispatchers.Default) { transcode.stdout.buffer().readByteArray() }
    assertEquals(0, transcode.exitCode.await(), "wav transcode should exit 0")

    // 2) Feed those bytes back through ffmpeg's stdin and decode to null.
    val proc = KmpProcessBuilder(bin)
        .appendArg("-i", "pipe:0", "-f", "null", "-")
        .discardStdout()
        .start()
    // Drive stdin ourselves; closing it (use) signals EOF to ffmpeg.
    proc.scope.launch { proc.stdin.buffer().use { it.write(wav) } }
    val err = withContext(Dispatchers.Default) { proc.stderr.buffer().readUtf8() }
    assertEquals(0, proc.exitCode.await(), "decoding WAV from stdin should exit 0:\n$err")
    assertTrue("Audio: pcm_s16le" in err, "stdin-fed WAV stream not decoded:\n$err")
}

/**
 * Args are passed as a real argv array (ProcessBuilder / execve) — never through a shell — so a value
 * with spaces reaches the child as one verbatim argument, no escaping needed.
 */
internal suspend fun ffmpegArgWithSpaceCheck() {
    val bin = FfmpegTestConfig.binaryPath ?: return

    val proc = KmpProcessBuilder(bin)
        .appendArg("-i", "file:/no/such dir/missing file.mp3")
        .discardStdout()
        .start()
    val err = withContext(Dispatchers.Default) { proc.stderr.buffer().readUtf8() }
    proc.exitCode.await()
    assertTrue("such dir/missing file.mp3" in err, "arg with spaces was mangled:\n$err")
}
