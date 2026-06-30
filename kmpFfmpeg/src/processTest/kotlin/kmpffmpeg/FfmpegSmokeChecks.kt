package kmpffmpeg

import kmpcommon.uri.Uri
import kmpffmpeg.metadata.FfmpegMetadataGetter
import kmpffmpeg.metadata.StreamMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Shared smoke-test bodies, invoked by every concrete [FfmpegSmokeTest] (host + Android device). Each
 * takes the platform's [FfmpegTestConfig] and skips itself when this platform ships no runnable ffmpeg
 * binary (`cfg.binaryPath == null`). Fixtures are the committed `sample_with_metadata.{m4a,mka}` under
 * `cfg.testDataDir`.
 */
internal suspend fun ffmpegSmokeChecks(cfg: FfmpegTestConfig) {
    val bin = cfg.binaryPath ?: return // no runnable binary → skip

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
 * Real media smoke test: run `ffmpeg -i` over the committed fixtures under `cfg.testDataDir` and assert
 * the embedded metadata + duration come back on stderr.
 */
internal suspend fun ffmpegMetadataChecks(cfg: FfmpegTestConfig) {
    val bin = cfg.binaryPath ?: return

    val fixtures = listOf(
        "sample_with_metadata.m4a",  // ipod/MP4 + AAC
        "sample_with_metadata.flac", // native FLAC
        "sample_with_metadata.mka",  // Matroska + AAC
    )
    for (name in fixtures) cfg.inputFactory(name).use { input ->
        val proc = KmpProcessBuilder(bin).appendArg("-i", input.spec).start()
        input.onProcessStarted(proc) // serve the fixture (file: on host, ymix on android)
        val err = withContext(Dispatchers.Default) { proc.stderr.buffer().readUtf8() }
        proc.exitCode.await()
        proc.close()
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
internal suspend fun ffmpegDiscardCheck(cfg: FfmpegTestConfig) {
    val bin = cfg.binaryPath ?: return

    cfg.inputFactory("sample_with_metadata.m4a").use { input ->
        val proc = KmpProcessBuilder(bin)
            .appendArg("-i", input.spec)
            .appendArg("-f", "wav", "pipe:1")
            .discardStdout()
            .discardStderr()
            .start()
        input.onProcessStarted(proc)

        val out = withContext(Dispatchers.Default) { proc.stdout.buffer().readUtf8() }
        val err = withContext(Dispatchers.Default) { proc.stderr.buffer().readUtf8() }
        assertEquals(0, proc.exitCode.await(), "transcode with discarded streams should exit 0")
        assertEquals("", out, "discarded stdout must read as EOF/empty")
        assertEquals("", err, "discarded stderr must read as EOF/empty")
        proc.close()
    }
}

/**
 * Captures a large piped stdout (a fixture transcoded to WAV, ~170 KB) and verifies the bytes arrive
 * intact — exercising the multi-segment read in the native source.
 */
internal suspend fun ffmpegLargeStdoutCheck(cfg: FfmpegTestConfig) {
    val bin = cfg.binaryPath ?: return

    cfg.inputFactory("sample_with_metadata.m4a").use { input ->
        val proc = KmpProcessBuilder(bin)
            .appendArg("-i", input.spec)
            .appendArg("-f", "wav", "pipe:1")
            .discardStderr()
            .start()
        input.onProcessStarted(proc)
        val wav = withContext(Dispatchers.Default) { proc.stdout.buffer().readByteArray() }
        assertEquals(0, proc.exitCode.await(), "wav transcode should exit 0")
        assertTrue(wav.size > 100_000, "expected a large WAV, got ${wav.size} bytes")
        assertEquals("RIFF", wav.copyOfRange(0, 4).decodeToString(), "captured stdout is not a WAV")
        proc.close()
    }
}

/**
 * Feeds a (large, streamable) WAV into ffmpeg's stdin and decodes it — exercising the native sink
 * (buffer → fd) write path. The caller drives stdin itself; this module only launches the process.
 */
internal suspend fun ffmpegStdinPipeCheck(cfg: FfmpegTestConfig) {
    val bin = cfg.binaryPath ?: return

    // 1) Produce a streamable WAV in memory (the fixture comes through the platform's input proxy).
    val wav = cfg.inputFactory("sample_with_metadata.m4a").use { input ->
        val transcode = KmpProcessBuilder(bin)
            .appendArg("-i", input.spec)
            .appendArg("-f", "wav", "pipe:1")
            .discardStderr()
            .start()
        input.onProcessStarted(transcode)
        val bytes = withContext(Dispatchers.Default) { transcode.stdout.buffer().readByteArray() }
        assertEquals(0, transcode.exitCode.await(), "wav transcode should exit 0")
        transcode.close()
        bytes
    }

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
 * End-to-end metadata read: drive the real ffmpeg binary through [FfmpegMetadataGetter] over each
 * fixture and assert the parsed container/stream model — exercising launch → stderr capture → parse.
 */
internal suspend fun ffmpegMetadataGetterChecks(cfg: FfmpegTestConfig) {
    val bin = cfg.binaryPath ?: return

    val getter = FfmpegMetadataGetter(bin)
    for (name in listOf(
        "sample_with_metadata.flac",
        "sample_with_metadata.m4a",
        "sample_with_metadata.mka"
    )) {
        val meta = getter.getMetadata(Uri.parse("file:${cfg.testDataDir}/$name"))

        assertEquals("Midnight Drive", meta.container.metadataMap.title, "title for $name")
        assertEquals("The Kotlin Quartet", meta.container.metadataMap.artist, "artist for $name")
        assertEquals("Multiplatform Sessions", meta.container.metadataMap.album, "album for $name")
        // ~2s fixtures; the .mka container rounds to 2.02s, so assert a tight range rather than exact.
        assertTrue(
            meta.container.duration!! in 2000..2100,
            "duration(ms) for $name was ${meta.container.duration}"
        )

        val audio = meta.streams.filterIsInstance<StreamMetadata.Audio>().single()
        assertEquals(44100, audio.sample, "sample rate for $name")
        assertEquals(1, audio.channels, "channels for $name")
        assertFalse(meta.hasVideo(), "$name should not be a video")
    }

    // A non-media file has no Input block → the getter surfaces a parse failure rather than bad data.
    assertFailsWith<kmpffmpeg.metadata.FfmpegInfoParseException> { getter.getMetadata(Uri.parse("file:$bin")) }
}

/**
 * The [FfInputResolver] seam: a custom resolver maps an opaque (content://-style) URI to a real `file:`
 * spec, and the getter must route through it — invoking `resolve`, `onProcessStarted`, and `close`, and
 * keeping the *logical* URI on the result (not the resolved spec). This is the hook Android uses to
 * proxy `content://` to `pipe:0`/`ymix:`; here we just remap to a file so there's no pipe lifecycle.
 */
internal suspend fun ffmpegInputResolverChecks(cfg: FfmpegTestConfig) {
    val bin = cfg.binaryPath ?: return

    var resolvedUri: String? = null
    var started = false
    var closed = false
    val resolver = FfInputResolver { uri ->
        resolvedUri = uri.toString()
        object : FfInput {
            override val spec = "file:${cfg.testDataDir}/sample_with_metadata.flac"
            override fun onProcessStarted(process: KmpProcess) {
                started = true
            }

            override fun close() {
                closed = true
            }
        }
    }

    val meta = FfmpegMetadataGetter(bin, resolver).getMetadata(Uri.parse("ymusic-content://42"))

    assertEquals("ymusic-content://42", resolvedUri, "resolver should see the logical uri")
    assertEquals("flac", meta.container.name, "should probe the resolved file: spec")
    assertEquals("Midnight Drive", meta.container.metadataMap.title)
    assertEquals(
        "ymusic-content://42",
        meta.uri.toString(),
        "result keeps the logical uri, not the spec"
    )
    assertTrue(started, "input.onProcessStarted() must be called")
    assertTrue(closed, "input.close() must be called")
}

/**
 * End-to-end space-in-path: copy a fixture to a name with spaces, then probe it through the default
 * (passthrough) getter using a percent-encoded `file:` URI. The resolver must decode it to a literal
 * path (ffmpeg's file protocol doesn't percent-decode), and the spaced arg must survive argv intact.
 */
internal suspend fun ffmpegSpacedFilePathCheck(cfg: FfmpegTestConfig) {
    val bin = cfg.binaryPath ?: return
    val dir = cfg.testDataDir
    val spaced = "$dir/with space probe.flac".toPath()
    FileSystem.SYSTEM.copy("$dir/sample_with_metadata.flac".toPath(), spaced)
    try {
        val meta =
            FfmpegMetadataGetter(bin).getMetadata(Uri.parse("file:$dir/with%20space%20probe.flac"))
        assertEquals("flac", meta.container.name, "encoded file: URI should resolve + open")
        assertEquals("Midnight Drive", meta.container.metadataMap.title)
    } finally {
        FileSystem.SYSTEM.delete(spaced)
    }
}

/**
 * Args are passed as a real argv array (ProcessBuilder / execve) — never through a shell — so a value
 * with spaces reaches the child as one verbatim argument, no escaping needed.
 */
internal suspend fun ffmpegArgWithSpaceCheck(cfg: FfmpegTestConfig) {
    val bin = cfg.binaryPath ?: return

    val proc = KmpProcessBuilder(bin)
        .appendArg("-i", "file:/no/such dir/missing file.mp3")
        .discardStdout()
        .start()
    val err = withContext(Dispatchers.Default) { proc.stderr.buffer().readUtf8() }
    proc.exitCode.await()
    assertTrue("such dir/missing file.mp3" in err, "arg with spaces was mangled:\n$err")
}
