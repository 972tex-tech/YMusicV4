package kmpffmpeg.metadata

import kmpcommon.uri.Uri
import kmpffmpeg.FfInputResolver
import kmpffmpeg.KmpProcessBuilder
import kmpffmpeg.autoClose
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.buffer
import okio.use
import kotlin.time.Duration.Companion.milliseconds

/**
 * Reads a media file's metadata by running `ffmpeg -i <input>` and parsing its stderr
 * ([FfmpegInfoParser]). `ffmpeg -i` with no output file always exits non-zero ("At least one output
 * file must be specified") *after* printing the info, so the exit code is ignored — success is "did
 * the dump contain an `Input #` block", which the parser decides.
 *
 * @param ffmpegBinary  absolute path to the prebuilt `ffmpeg` executable.
 * @param inputResolver maps the requested [Uri] to the actual ffmpeg input. Defaults to
 *                      [FfInputResolver.Passthrough] (`file:`/`http:`/`pipe:`/`ymix:`); supply an
 *                      Android resolver to proxy `content://` (see [FfInputResolver]).
 * @param environment   extra env vars for the child (e.g. `LD_LIBRARY_PATH` on Linux/Android).
 */
open class FfmpegMetadataGetter(
    private val ffmpegBinary: String,
    private val inputResolver: FfInputResolver = FfInputResolver.Passthrough,
    private val environment: Map<String, String> = emptyMap(),
) {
    /**
     * @param uri       the logical media source; [inputResolver] turns it into ffmpeg's `-i` spec. The
     *                  original [uri] (not the resolved spec) is stored on the result.
     * @param timeoutMs hard cap on the probe; the child is terminated if it overruns.
     */
    suspend fun getMetadata(uri: Uri, timeoutMs: Long = 3000): MediaFileMetadata =
        // Run on Dispatchers.Default (not the caller's context) so withTimeout uses real wall-clock
        // delay — under kotlinx-coroutines-test's virtual time it would otherwise fire instantly.
        withContext(Dispatchers.Default) {
            inputResolver.resolve(uri).use { input ->
                KmpProcessBuilder(ffmpegBinary)
                    .appendArg("-hide_banner", "-i", input.spec)
                    .setEnv(environment)
                    .discardStdout() // ffmpeg -i writes the info to stderr; stdout stays empty
                    .start()
                    .autoClose { process ->
                        input.onProcessStarted(process) // e.g. start feeding pipe:0 / serving ymix

                        // Read stderr on the process's OWN scope: the blocking fd read isn't cancellable, so
                        // on timeout we let process.close() kill the child — that makes the read hit EOF, and
                        // close() then joins this job before freeing the fd. (exit is expected non-zero.)
                        val readStderr = process.scope.async { process.stderr.buffer().readUtf8() }
                        val stderr = withTimeout(timeoutMs.milliseconds) { readStderr.await() }
                        process.exitCode.await() // reap on the normal path (close() below won't wait)
                        FfmpegInfoParser.parse(uri, stderr)
                    }
            }
        }
}
