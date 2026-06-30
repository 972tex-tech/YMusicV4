package kmpffmpeg

import android.net.Uri
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import kmpffmpeg.metadata.FfmpegMetadataGetter
import kmpffmpeg.metadata.StreamMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * End-to-end tests that exec the REAL prebuilt android ffmpeg and drive a media fixture through the
 * `ymix` proxy. The binary is shipped in the test APK as `lib/<abi>/libffmpeg.so` (staged into jniLibs
 * by `:kmpFfmpeg:stageFfmpegDeviceTestJni`) and, because `useLegacyPackaging = true` forces
 * `extractNativeLibs`, the installer drops it into `nativeLibraryDir` as a real, exec-able file.
 *
 * The fixture ([FfmpegAndroidFixtures]) is the committed `sample_with_metadata.m4a` embedded as base64;
 * it is written to the device cache and exposed to the server via a `file://` uri ([Uri.fromFile]),
 * which [android.content.ContentResolver.openFileDescriptor] opens natively — no `ContentProvider`
 * needed. Unlike [FfYmixProtocolServerTest] (which speaks the protocol with a `LocalSocket`), these run ffmpeg
 * itself as the ymix client.
 */
class FfmpegRealBinaryTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val contentResolver get() = context.contentResolver

    /** The extracted ffmpeg executable: `<nativeLibraryDir>/libffmpeg.so`. */
    private val ffmpegBinary: String
        get() = File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so").absolutePath

    private lateinit var fixture: File

    @Before
    fun materializeFixture() {
        fixture = File(context.cacheDir, "sample_with_metadata.m4a").apply {
            writeBytes(Base64.decode(FfmpegAndroidFixtures.filesBase64.getValue("sample_with_metadata.m4a"), Base64.DEFAULT))
        }
    }

    /** The binary actually execs on this device/ABI. */
    @Test
    fun execsVersion() = runBlocking {
        val proc = KmpProcessBuilder(ffmpegBinary).appendArg("-version").discardStderr().start()
        val out = withContext(Dispatchers.IO) { proc.stdout.buffer().readUtf8() }
        assertEquals("ffmpeg -version should exit 0", 0, proc.exitCode.await())
        assertTrue("unexpected -version output: ${out.take(60)}", out.startsWith("ffmpeg version"))
        proc.close()
    }

    /** Real ffmpeg reads the fixture through a ymix READ connection; its `-i` diagnostics come back intact. */
    @Test
    fun readsFixtureThroughYmixProxy() = runBlocking {
        val server = FfYmixProtocolServer(contentResolver, Uri.fromFile(fixture)).apply { start() }
        try {
            val proc = KmpProcessBuilder(ffmpegBinary).appendArg("-i", server.url).start()
            server.onProcessStarted(proc)
            val err = withContext(Dispatchers.IO) { proc.stderr.buffer().readUtf8() }
            proc.exitCode.await()
            assertTrue("title missing:\n$err", "Midnight Drive" in err)
            assertTrue("artist missing:\n$err", "The Kotlin Quartet" in err)
            assertTrue("duration missing:\n$err", "Duration: 00:00:02" in err)
            assertTrue("audio stream missing:\n$err", "Audio:" in err)
            proc.close()
        } finally {
            server.close()
        }
    }

    /** The full Android stack: [FfmpegMetadataGetter] over a ymix-backed [FfInput] → parsed model. */
    @Test
    fun metadataGetterThroughYmixProxy() = runBlocking {
        val server = FfYmixProtocolServer(contentResolver, Uri.fromFile(fixture)).apply { start() }
        val input = object : FfInput {
            override val spec = server.url
            override fun onProcessStarted(process: KmpProcess) = server.onProcessStarted(process)
            override fun close() = server.close()
        }
        val meta = FfmpegMetadataGetter(ffmpegBinary, FfInputResolver { input })
            .getMetadata(kmpcommon.uri.Uri.parse("content://probe/1"))

        assertEquals("Midnight Drive", meta.container.metadataMap.title)
        assertEquals("The Kotlin Quartet", meta.container.metadataMap.artist)
        assertTrue("duration(ms) was ${meta.container.duration}", meta.container.duration!! in 2000..2100)
        val audio = meta.streams.filterIsInstance<StreamMetadata.Audio>().single()
        assertEquals(44100L, audio.sample)
        // result keeps the logical uri, not the ymix spec
        assertEquals("content://probe/1", meta.uri.toString())
    }

    /**
     * Real ffmpeg writes its OUTPUT through a ymix WRITE connection, remuxing the fixture to mp4 with
     * `+faststart`. faststart makes the muxer write the file, then re-open its own output read-only and
     * read it back to relocate the moov atom to the front — under ymix that read-back is a SECOND,
     * independent connection to the socket, exercising the write + concurrent-read path against the real
     * binary. (The prebuilt has no WAV muxer; its muxer set is ipod/mp3/mp4/webm/opus/image2.)
     */
    @Test
    fun writesFaststartMp4ThroughYmixProxy() = runBlocking {
        val outFile = File(context.cacheDir, "proxy-out.m4a").apply { delete() }
        val server = FfYmixProtocolServer(contentResolver, Uri.fromFile(outFile)).apply { start() }
        try {
            val proc = KmpProcessBuilder(ffmpegBinary)
                .appendArg("-i", "file:${fixture.absolutePath}")
                .appendArg("-c", "copy", "-f", "mp4", "-movflags", "+faststart", server.url)
                .discardStdout()
                .start()
            server.onProcessStarted(proc)
            val err = withContext(Dispatchers.IO) { proc.stderr.buffer().readUtf8() }
            assertEquals("remux to faststart mp4 via write proxy should exit 0:\n$err", 0, proc.exitCode.await())
            proc.close()
        } finally {
            server.close()
        }
        assertTrue("output mp4 too small: ${outFile.length()}", outFile.length() > 10_000)
        // An mp4 begins with an `ftyp` box: 4-byte size, then the type at offset 4.
        assertEquals("output is not an mp4", "ftyp", outFile.readBytes().copyOfRange(4, 8).decodeToString())

        // Re-probe the proxy-written file with ffmpeg: proves faststart produced a VALID mp4 (the
        // moov-relocation read-back over the proxy didn't corrupt it), not just a plausible header.
        val probe = KmpProcessBuilder(ffmpegBinary).appendArg("-i", "file:${outFile.absolutePath}").start()
        val probeErr = withContext(Dispatchers.IO) { probe.stderr.buffer().readUtf8() }
        probe.exitCode.await()
        probe.close()
        assertTrue("proxy-written mp4 didn't probe as audio:\n$probeErr", "Audio:" in probeErr)
        assertTrue("proxy-written mp4 lost its title tag:\n$probeErr", "Midnight Drive" in probeErr)
    }

    /**
     * A fatal proxy error must reach the app as the TYPED cause, not a bare non-zero exit. We back a read
     * server with a missing file: ffmpeg's first contact makes the handler's `openFileDescriptor` throw
     * `FileNotFoundException`, which the server reports by failing the process — so awaiting `exitCode`
     * throws that exact cause (the `onProcessStarted` + `close(error)` wiring in [FfYmixProtocolServer]).
     */
    @Test
    fun proxyFailureSurfacesAsTypedCause() = runBlocking {
        val missing = Uri.fromFile(File(context.cacheDir, "no-such-${System.nanoTime()}.bin"))
        val server = FfYmixProtocolServer(contentResolver, missing).apply { start() }
        try {
            val proc = KmpProcessBuilder(ffmpegBinary).appendArg("-i", server.url).discardStderr().start()
            server.onProcessStarted(proc)
            val cause = runCatching { proc.exitCode.await() }.exceptionOrNull()
            assertTrue(
                "expected the proxy failure to surface via exitCode as a typed cause, got: $cause",
                cause is java.io.FileNotFoundException,
            )
            proc.close()
        } finally {
            server.close()
        }
    }
}
