package kmpffmpeg

import android.content.ContentResolver
import android.net.Uri
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/**
 * Android concrete [FfmpegSmokeTest]: runs the shared smoke suite on the emulator, with every fixture
 * served through the **ymix** proxy instead of a plain `file:` — so the suite exercises the real
 * `FfYmixProtocolServer` read path end-to-end against the prebuilt binary (version / probe / transcode
 * / stdin-feed), on top of the targeted ymix tests in [FfmpegRealBinaryTest].
 *
 * The base class + check bodies are pulled into this (separate) source-set tree by `srcDir`, since the
 * on-device test set can't `dependsOn` `processTest`. The binary is the `libffmpeg.so` the installer
 * extracted into `nativeLibraryDir`; the fixtures are the base64-embedded [FfmpegAndroidFixtures]
 * materialized once into the app cache.
 */
class AndroidSmokeTest : FfmpegSmokeTest() {
    override val config: FfmpegTestConfig by lazy {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val binary = File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so").absolutePath
        val dir = File(context.cacheDir, "ffmpeg-fixtures").apply { mkdirs() }
        FfmpegAndroidFixtures.filesBase64.forEach { (name, b64) ->
            File(dir, name).writeBytes(Base64.decode(b64, Base64.DEFAULT))
        }
        FfmpegTestConfig(
            binaryPath = binary,
            testDataDir = dir.absolutePath,
            inputFactory = { name -> ymixInput(context.contentResolver, File(dir, name)) },
        )
    }

    /** Serves [file] over a fresh [FfYmixProtocolServer]; the returned [FfInput] owns it (closed by the check). */
    private fun ymixInput(contentResolver: ContentResolver, file: File): FfInput {
        val server = FfYmixProtocolServer(contentResolver, Uri.fromFile(file)).apply { start() }
        return object : FfInput {
            override val spec = server.url
            override fun onProcessStarted(process: KmpProcess) = server.onProcessStarted(process)
            override fun close() = server.close()
        }
    }
}
