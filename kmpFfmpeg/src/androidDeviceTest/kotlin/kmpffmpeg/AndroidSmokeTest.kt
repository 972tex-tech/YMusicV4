package kmpffmpeg

import android.content.ContentResolver
import android.net.Uri
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/**
 * Android concrete smoke suites: run the shared [KmpProcessSmokeTest] + [FfmpegMetadataGetterSmokeTest]
 * on the emulator, with every fixture served through the **ymix** proxy instead of a plain `file:` — so
 * the process-level suite exercises the real [FfYmixProtocolServer] read path end-to-end against the
 * prebuilt binary (version / probe / transcode / stdin-feed), on top of the targeted ymix tests in
 * [FfmpegRealBinaryTest].
 *
 * The base classes + check bodies are pulled into this (separate) source-set tree by `srcDir`, since the
 * on-device test set can't `dependsOn` `processTest`. [AndroidFfmpegTestEnv] resolves the binary +
 * fixtures once at runtime and both suites share it.
 */
class AndroidKmpProcessTest : KmpProcessSmokeTest() {
    override val config get() = AndroidFfmpegTestEnv.config
}

class AndroidFfmpegMetadataGetterTest : FfmpegMetadataGetterSmokeTest() {
    override val config get() = AndroidFfmpegTestEnv.config
}

/** Resolves the on-device ffmpeg + fixtures once, serving every fixture over a fresh ymix server. */
private object AndroidFfmpegTestEnv {
    val config: FfmpegTestConfig by lazy {
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
